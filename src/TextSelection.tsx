import { useEffect, useMemo, useRef, useState } from 'react';
import { Image, PanResponder, StyleSheet, View, type LayoutChangeEvent, type ViewProps } from 'react-native';
import type { TextCapture, TextRegion, TextWord } from './TextCamera.nitro';

export interface TextSelectionProps extends Omit<ViewProps, 'children'> {
  /** A text session's capture, `TextSessionSnapshot.capture`. */
  capture: TextCapture;
  /** Fill over selected words; defaults to translucent teal. */
  selectionColor?: string;
  /** Fill over every recognized word, so the user sees what can be picked; defaults to a faint white. */
  wordColor?: string;
  /** The selected words, in reading order, and their text: words of a line joined by spaces, lines by newlines. */
  onSelectionChange?: (words: TextWord[], text: string) => void;
  /**
   * Where the fitted image sits in the space left by `imageInsets`, as fractions along each axis, as CSS
   * `object-position` does: 0 at the start, 1 at the end; defaults to 0.5, centred. Taps and drags still work over the
   * whole view, so a full-screen selection can show its image at the bottom and still be dragged anywhere.
   */
  imagePosition?: { x?: number; y?: number };
  /** Space kept clear around the image, in points, as for controls over the view; the gesture area stays whole. */
  imageInsets?: { top?: number; right?: number; bottom?: number; left?: number };
}

/** How far, as a fraction of the image's longer side, a tap may land from a word and still pick it. */
const TAP_SLOP = 0.04;
/** A press that moves less than this many points is a tap; past it the press drags a box. */
const DRAG_MIN = 24;
/** How many points above the finger a dragged box's moving corner sits, as a map pin does, so the finger never hides
 * it. */
const LIFT = 48;

/** The text of [words]: a line's words joined by spaces, lines by newlines. */
export function selectedText(words: readonly TextWord[]): string {
  let text = '';
  words.forEach((word, index) => {
    if (index > 0) text += words[index - 1]!.line === word.line ? ' ' : '\n';
    text += word.text;
  });
  return text;
}

/**
 * The box a drag from [start] to [finger] draws, in view points, or null while it has no area. It starts exactly where
 * the press began; its moving corner rises above the finger by up to [lift], by as much as the finger has moved
 * vertically, so the box does not jump on the first move: dragging down, it opens once the finger is [lift] below the
 * start, and dragging up, it opens at once, its corner reaching [lift] above the finger.
 */
export function dragBox(start: { x: number; y: number }, finger: { x: number; y: number }, lift: number) {
  const cornerY = finger.y - Math.min(lift, Math.abs(finger.y - start.y));
  const rect = {
    x: Math.min(start.x, finger.x),
    y: Math.min(start.y, cornerY),
    width: Math.abs(finger.x - start.x),
    height: Math.abs(cornerY - start.y),
  };
  return rect.width > 0 && rect.height > 0 ? rect : null;
}

/** Where a [width] by [height] image fits in a view of [size], inside [insets], placed at [position] along each axis. */
export function fitImage(size: { width: number; height: number }, width: number, height: number,
  position: { x?: number; y?: number } = {}, insets: { top?: number; right?: number; bottom?: number; left?: number } = {}) {
  const left = insets.left ?? 0;
  const top = insets.top ?? 0;
  const areaWidth = size.width - left - (insets.right ?? 0);
  const areaHeight = size.height - top - (insets.bottom ?? 0);
  const scale = areaWidth > 0 && areaHeight > 0 ? Math.min(areaWidth / width, areaHeight / height) : 0;
  const clamp = (value: number | undefined) => Math.min(Math.max(value ?? 0.5, 0), 1);
  return {
    left: left + (areaWidth - width * scale) * clamp(position.x),
    top: top + (areaHeight - height * scale) * clamp(position.y),
    width: width * scale,
    height: height * scale,
  };
}

/** The centre of [word]'s corners, in image fractions. */
function centreOf({ corners }: TextWord) {
  const { topLeft: a, topRight: b, bottomRight: c, bottomLeft: d } = corners;
  return { x: (a.x + b.x + c.x + d.x) / 4, y: (a.y + b.y + c.y + d.y) / 4 };
}

/** The words whose centre lies inside [box], in image fractions, in reading order. */
export function wordsInBox(words: readonly TextWord[], box: TextRegion): TextWord[] {
  return words.filter((word) => {
    const { x, y } = centreOf(word);
    return x >= box.x && x <= box.x + box.width && y >= box.y && y <= box.y + box.height;
  });
}

/** [word] as a rectangle turned with its text, in a [width] by [height] frame from ([left], [top]): its centre,
 * its length along the text and its height across it, and the angle of its top edge in radians. */
export function wordShape(word: TextWord, left: number, top: number, width: number, height: number) {
  const point = ({ x, y }: { x: number; y: number }) => ({ x: left + x * width, y: top + y * height });
  const a = point(word.corners.topLeft);
  const b = point(word.corners.topRight);
  const c = point(word.corners.bottomRight);
  const d = point(word.corners.bottomLeft);
  // The mean of opposite edges, so a slightly uneven box still turns as its text does.
  const along = { x: (b.x - a.x + c.x - d.x) / 2, y: (b.y - a.y + c.y - d.y) / 2 };
  const across = { x: (d.x - a.x + c.x - b.x) / 2, y: (d.y - a.y + c.y - b.y) / 2 };
  return {
    x: (a.x + b.x + c.x + d.x) / 4,
    y: (a.y + b.y + c.y + d.y) / 4,
    length: Math.hypot(along.x, along.y),
    thickness: Math.hypot(across.x, across.y),
    angle: Math.atan2(along.y, along.x),
  };
}

/** The word under ([x], [y]), view points, else the nearest within [slop] points of its turned box; undefined for
 * none. Each word's shape is measured in the image fitted at [fit]. */
function wordAt(words: readonly TextWord[], x: number, y: number, fit: { left: number; top: number; width: number; height: number },
  slop: number): TextWord | undefined {
  let best: TextWord | undefined;
  let bestDistance = Infinity;
  for (const word of words) {
    const shape = wordShape(word, fit.left, fit.top, fit.width, fit.height);
    // The tap in the word's own frame, turned back by its angle.
    const cos = Math.cos(shape.angle);
    const sin = Math.sin(shape.angle);
    const along = (x - shape.x) * cos + (y - shape.y) * sin;
    const across = -(x - shape.x) * sin + (y - shape.y) * cos;
    const distance = Math.hypot(Math.max(Math.abs(along) - shape.length / 2, 0), Math.max(Math.abs(across) - shape.thickness / 2, 0));
    if (distance < bestDistance) {
      best = word;
      bestDistance = distance;
    }
  }
  return bestDistance <= slop ? best : undefined;
}

/**
 * A captured still of the text region, fitted to the view at `imagePosition` inside `imageInsets`, with every
 * recognized word shown faintly: tap a word to select it, or drag a box over the words wanted, anywhere over the view.
 * The box starts exactly where the press began, and its moving corner hangs above the finger, as a map pin does
 * (`dragBox`).
 */
export function TextSelection({
  capture,
  selectionColor = 'rgba(97, 225, 207, 0.45)',
  wordColor = 'rgba(255, 255, 255, 0.18)',
  onSelectionChange,
  imagePosition,
  imageInsets,
  style,
  ...props
}: TextSelectionProps) {
  const [size, setSize] = useState({ width: 0, height: 0 });
  const [selected, setSelected] = useState<ReadonlySet<TextWord>>(new Set());
  const [box, setBox] = useState<TextRegion | null>(null);
  // A new capture starts with nothing selected.
  useEffect(() => setSelected(new Set()), [capture]);
  const image = fitImage(size, capture.width, capture.height, imagePosition, imageInsets);
  const scale = image.width > 0 ? image.width / capture.width : 0;
  const live = useRef({ image, capture, onSelectionChange });
  live.current = { image, capture, onSelectionChange };

  const responder = useMemo(() => {
    let start = { x: 0, y: 0 };
    let dragging = false;
    const toImage = (x: number, y: number) => {
      const { image: fit } = live.current;
      return fit.width > 0 ? { x: (x - fit.left) / fit.width, y: (y - fit.top) / fit.height } : { x: -1, y: -1 };
    };
    const show = (picked: TextWord[], done: boolean) => {
      setSelected(new Set(picked));
      if (done) live.current.onSelectionChange?.(picked, selectedText(picked));
    };
    const track = (x: number, y: number, done: boolean) => {
      const { capture: shot } = live.current;
      // Once a press drags, it stays a drag even if the finger comes back near where it started.
      if (!dragging && Math.hypot(x - start.x, y - start.y) >= DRAG_MIN) dragging = true;
      if (!dragging) {
        if (!done) return;
        // A tap picks the word under the finger.
        const fit = live.current.image;
        const word = wordAt(shot.words, start.x, start.y, fit, TAP_SLOP * Math.max(fit.width, fit.height));
        show(word ? [word] : [], true);
        return;
      }
      const drawn = dragBox(start, { x, y }, LIFT);
      if (drawn == null) {
        setBox(null);
        show([], done);
        return;
      }
      const from = toImage(drawn.x, drawn.y);
      const to = toImage(drawn.x + drawn.width, drawn.y + drawn.height);
      const rect = { x: from.x, y: from.y, width: to.x - from.x, height: to.y - from.y };
      setBox(done ? null : rect);
      show(wordsInBox(shot.words, rect), done);
    };
    return PanResponder.create({
      onStartShouldSetPanResponder: () => true,
      onMoveShouldSetPanResponder: () => true,
      // A drag over the picture is a selection, not a scroll or a sheet's swipe around it.
      onPanResponderTerminationRequest: () => false,
      onShouldBlockNativeResponder: () => true,
      // The children take no touches, so the location is always within this view.
      onPanResponderGrant: ({ nativeEvent }) => {
        start = { x: nativeEvent.locationX, y: nativeEvent.locationY };
        dragging = false;
      },
      onPanResponderMove: (_, gesture) => track(start.x + gesture.dx, start.y + gesture.dy, false),
      onPanResponderRelease: (_, gesture) => track(start.x + gesture.dx, start.y + gesture.dy, true),
      onPanResponderTerminate: () => setBox(null),
    });
  }, []);

  const onLayout = (event: LayoutChangeEvent) => {
    const { width, height } = event.nativeEvent.layout;
    setSize({ width, height });
    props.onLayout?.(event);
  };

  // A word's box turns with its text: sized along and across it around its centre, then rotated there.
  const placeWord = (word: TextWord) => {
    const shape = wordShape(word, image.left, image.top, image.width, image.height);
    return {
      left: shape.x - shape.length / 2,
      top: shape.y - shape.thickness / 2,
      width: shape.length,
      height: shape.thickness,
      transform: [{ rotate: `${shape.angle}rad` }],
    };
  };

  const place = (bounds: TextRegion) => ({
    left: image.left + bounds.x * image.width,
    top: image.top + bounds.y * image.height,
    width: bounds.width * image.width,
    height: bounds.height * image.height,
  });

  return (
    <View {...props} style={[styles.container, style]} onLayout={onLayout} {...responder.panHandlers}>
      {scale > 0 && (
        // Touches land on the container itself, whose own coordinates the gesture uses; a touch landing on a child
        // would measure from that child's corner.
        <View style={StyleSheet.absoluteFill} pointerEvents="none">
          <Image source={{ uri: capture.uri }} style={[styles.absolute, image]} resizeMode="stretch" />
          {capture.words.map((word, index) => (
            <View
              key={index}
              style={[styles.absolute, styles.word, placeWord(word), { backgroundColor: selected.has(word) ? selectionColor : wordColor }]}
            />
          ))}
          {box && <View style={[styles.absolute, styles.box, place(box)]} />}
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  // Image coordinates run left to right whatever the app's direction: in RTL React Native measures `left` from the
  // right edge, which would mirror every box over the picture while touches still report physical coordinates.
  container: { overflow: 'hidden', direction: 'ltr' },
  absolute: { position: 'absolute' },
  word: { borderRadius: 3 },
  box: { borderWidth: 1.5, borderColor: 'rgba(97, 225, 207, 0.9)', backgroundColor: 'rgba(97, 225, 207, 0.12)' },
});
