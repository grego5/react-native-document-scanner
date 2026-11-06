"""Label local document corners and save scanner-compatible JSON metadata only."""
import argparse
import hashlib
import json
import math
import sys
from pathlib import Path

from PIL import Image, ImageOps

EXTENSIONS = {'.jpg', '.jpeg', '.png', '.webp', '.bmp', '.tif', '.tiff'}
CORNERS = ('topLeft', 'topRight', 'bottomRight', 'bottomLeft')
CORNER_LABELS = ('TL', 'TR', 'BR', 'BL')
PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_IMAGES = Path.home() / ('Downloads' if sys.platform == 'win32' else 'Pictures') / 'samples'
DEFAULT_ANNOTATIONS = PROJECT_ROOT / '.local/label-samples/annotations.json'
DEFAULT_OUTPUT = PROJECT_ROOT / '.local/label-samples/labels.json'


def upright(path):
    with Image.open(path) as image:
        return ImageOps.exif_transpose(image).convert('RGB')


def save_manifest(path, manifest):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + '\n', encoding='utf-8', newline='\n')
    temporary.replace(path)


def read_sample(path, digest):
    """Status and quad from a scanner sample saved beside [path] as `<stem>.json`, or None without one."""
    sidecar = path.with_suffix('.json')
    if not sidecar.is_file():
        return None
    sample = json.loads(sidecar.read_text(encoding='utf-8'))
    if sample.get('version') != 2 or sample.get('coordinateSpace') != 'exifUprightNormalized':
        return None
    if sample.get('sha256', digest) != digest:
        raise ValueError(f'Image changed since its sample was saved: {path.name}')
    return dict(status=sample.get('status', 'unlabeled'), quad=sample.get('quad'))


def write_sample(path, sample):
    """Records [sample]'s status and quad in the scanner sample beside [path], keeping its other fields."""
    sidecar = Path(path).with_suffix('.json')
    if not sidecar.is_file():
        return
    saved = json.loads(sidecar.read_text(encoding='utf-8'))
    saved.update(status=sample['status'], quad=sample['quad'])
    save_manifest(sidecar, saved)


def initialize(images, annotations):
    images, annotations = Path(images).expanduser().resolve(), Path(annotations)
    if not images.is_dir():
        raise ValueError(f'Image directory does not exist: {images}')
    if annotations.exists():
        manifest = json.loads(annotations.read_text(encoding='utf-8'))
        if manifest.get('version') != 2 or manifest.get('coordinateSpace') != 'exifUprightNormalized':
            raise ValueError('Expected scanner JSON metadata; choose a new annotation filename for older formats')
        if Path(manifest['imageRoot']) != images:
            raise ValueError('Annotation file belongs to a different image directory')
    else:
        manifest = dict(version=2, imageRoot=str(images), coordinateSpace='exifUprightNormalized', images={})
    for path in sorted(images.iterdir()):
        if not path.is_file() or path.suffix.lower() not in EXTENSIONS:
            continue
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        if path.name in manifest['images']:
            if manifest['images'][path.name]['sha256'] != digest:
                raise ValueError(f'Image changed since labeling: {path.name}')
            continue
        image = upright(path)
        seed = read_sample(path, digest) or dict(status='unlabeled', quad=None)
        manifest['images'][path.name] = dict(originalUri=path.as_uri(), sha256=digest, sourceWidth=image.width, sourceHeight=image.height,
                                            **seed)
        image.close()
    if not manifest['images']:
        raise ValueError('No supported images found')
    save_manifest(annotations, manifest)
    return manifest


def validate_sample(sample):
    if sample['status'] == 'no-document':
        if sample.get('quad') is not None:
            raise ValueError('A no-document image cannot have a document quad')
        return
    if sample['status'] != 'document':
        raise ValueError('Image has not been labeled')
    quad = sample.get('quad')
    if not isinstance(quad, dict) or set(quad) != set(CORNERS):
        raise ValueError('Mark four corners: TL, TR, BR, BL')
    if not all(isinstance(quad[corner], dict) for corner in CORNERS):
        raise ValueError('Each corner must contain x and y coordinates')
    points = [[quad[corner].get('x'), quad[corner].get('y')] for corner in CORNERS]
    for point in points:
        if len(point) != 2 or not all(isinstance(v, (int, float)) and math.isfinite(v) and 0 <= v <= 1 for v in point):
            raise ValueError('Corner coordinates must be finite and inside the image')
    cross = []
    for i in range(4):
        a, b, c = points[i], points[(i + 1) % 4], points[(i + 2) % 4]
        cross.append((b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0]))
    if not all(value > 1e-6 for value in cross):
        raise ValueError('Corners must form a convex outline in TL → TR → BR → BL order')


def export_labels(annotations, output):
    annotations, output = Path(annotations), Path(output)
    if output.exists():
        raise FileExistsError(f'Output already exists; choose a new filename: {output}')
    manifest = json.loads(annotations.read_text(encoding='utf-8'))
    if manifest.get('coordinateSpace') != 'exifUprightNormalized' or manifest.get('version') != 2:
        raise ValueError('Expected scanner JSON metadata')
    reviewed = {}
    for name, sample in manifest['images'].items():
        if sample['status'] == 'unlabeled':
            continue
        validate_sample(sample)
        path = Path(manifest['imageRoot']) / name
        if hashlib.sha256(path.read_bytes()).hexdigest() != sample['sha256']:
            raise ValueError(f'Image changed since labeling: {name}')
        reviewed[name] = sample
    if not reviewed:
        raise ValueError('No reviewed photos to export')
    save_manifest(output, dict(manifest, images=reviewed))
    return dict(exported=len(reviewed), skippedUnlabeled=len(manifest['images']) - len(reviewed), output=str(output))


class Labeler:
    def __init__(self, images, annotations):
        import tkinter as tk
        from tkinter import messagebox
        from PIL import ImageTk
        self.tk, self.messagebox, self.ImageTk = tk, messagebox, ImageTk
        self.annotations = Path(annotations)
        self.manifest = initialize(images, annotations)
        self.names = list(self.manifest['images'])
        self.index = next((i for i, name in enumerate(self.names)
                           if self.manifest['images'][name]['status'] == 'unlabeled'), 0)
        self.root = tk.Tk()
        self.root.title('Document corner labels — local photos')
        self.root.geometry('1200x900')
        tk.Label(self.root, text='Click TL → TR → BR → BL. Drag points to adjust. Wheel: zoom. Right-drag: pan.').pack()
        bar = tk.Frame(self.root); bar.pack(fill='x')
        for text, command in [('Previous', lambda: self.navigate(-1)), ('Next / skip', lambda: self.navigate(1)),
                              ('Clear points', self.clear), ('Fit image', self.fit),
                              ('Save document + next', self.save_document), ('No document + save/next', self.save_no_document)]:
            tk.Button(bar, text=text, command=command).pack(side='left')
        self.status = tk.StringVar()
        tk.Label(self.root, textvariable=self.status).pack()
        self.canvas = tk.Canvas(self.root, bg='#222222', highlightthickness=0)
        self.canvas.pack(fill='both', expand=True)
        self.canvas.bind('<Button-1>', self.click)
        self.canvas.bind('<B1-Motion>', self.drag)
        self.canvas.bind('<ButtonRelease-1>', lambda event: setattr(self, 'selected', None))
        self.canvas.bind('<MouseWheel>', lambda event: self.zoom(event, 1.25 if event.delta > 0 else .8))
        if self.root.tk.call('tk', 'windowingsystem') == 'x11':
            self.canvas.bind('<Button-4>', lambda event: self.zoom(event, 1.25))
            self.canvas.bind('<Button-5>', lambda event: self.zoom(event, .8))
        self.canvas.bind('<Button-3>', lambda event: setattr(self, 'pan_start', (event.x, event.y)))
        self.canvas.bind('<B3-Motion>', self.pan)
        self.canvas.bind('<Configure>', lambda event: self.render())
        self.image = None
        self.points = []
        self.selected = None
        self.dirty = False
        self.root.protocol('WM_DELETE_WINDOW', self.close)
        self.root.after(100, self.load)

    def confirm_discard(self):
        return not self.dirty or self.messagebox.askyesno('Unsaved points', 'Discard the unsaved corner edits?')

    def close(self):
        if self.confirm_discard():
            self.root.destroy()

    def navigate(self, change):
        if self.confirm_discard():
            self.index = (self.index + change) % len(self.names)
            self.load()

    def load(self):
        name = self.names[self.index]
        if self.image is not None:
            self.image.close()
        self.image = upright(Path(self.manifest['imageRoot']) / name)
        sample = self.manifest['images'][name]
        quad = sample.get('quad')
        self.points = [(quad[c]['x'] * self.image.width, quad[c]['y'] * self.image.height) for c in CORNERS] if quad else []
        self.dirty = False
        self.selected = None
        self.fit()

    def fit(self):
        if self.image is None:
            return
        width, height = max(1, self.canvas.winfo_width()), max(1, self.canvas.winfo_height())
        self.scale = min(width / self.image.width, height / self.image.height)
        self.offset = [(width - self.image.width * self.scale) / 2, (height - self.image.height * self.scale) / 2]
        self.render()

    def point(self, event):
        x = (event.x - self.offset[0]) / self.scale
        y = (event.y - self.offset[1]) / self.scale
        return max(0, min(self.image.width, x)), max(0, min(self.image.height, y))

    def click(self, event):
        self.selected = next((i for i, (x, y) in enumerate(self.points)
                              if math.hypot(event.x - (x * self.scale + self.offset[0]),
                                            event.y - (y * self.scale + self.offset[1])) < 14), None)
        if self.selected is None and len(self.points) < 4:
            x, y = self.point(event)
            # Ignore clicks in the empty margin outside the photo.
            if not (0 <= (event.x - self.offset[0]) / self.scale <= self.image.width
                    and 0 <= (event.y - self.offset[1]) / self.scale <= self.image.height):
                return
            self.points.append((x, y)); self.selected = len(self.points) - 1
            self.dirty = True
        self.render()

    def drag(self, event):
        if self.selected is not None:
            self.points[self.selected] = self.point(event)
            self.dirty = True
            self.render()

    def clear(self):
        self.points = []; self.dirty = True; self.render()

    def zoom(self, event, factor):
        point = self.point(event)
        self.scale = max(.02, min(12., self.scale * factor))
        self.offset = [event.x - point[0] * self.scale, event.y - point[1] * self.scale]
        self.render()

    def pan(self, event):
        dx, dy = event.x - self.pan_start[0], event.y - self.pan_start[1]
        self.offset[0] += dx; self.offset[1] += dy
        self.pan_start = (event.x, event.y)
        self.render()

    def render(self):
        if self.image is None:
            return
        self.canvas.delete('all')
        width, height = self.canvas.winfo_width(), self.canvas.winfo_height()
        x0 = max(0, int(-self.offset[0] / self.scale))
        y0 = max(0, int(-self.offset[1] / self.scale))
        x1 = min(self.image.width, math.ceil((width - self.offset[0]) / self.scale))
        y1 = min(self.image.height, math.ceil((height - self.offset[1]) / self.scale))
        if x1 > x0 and y1 > y0:
            view = self.image.crop((x0, y0, x1, y1)).resize((max(1, round((x1 - x0) * self.scale)),
                                                         max(1, round((y1 - y0) * self.scale))))
            self.photo = self.ImageTk.PhotoImage(view)
            self.canvas.create_image(x0 * self.scale + self.offset[0], y0 * self.scale + self.offset[1],
                                     image=self.photo, anchor='nw')
        points = [(x * self.scale + self.offset[0], y * self.scale + self.offset[1]) for x, y in self.points]
        if len(points) >= 2:
            self.canvas.create_line(*(value for point in points + ([points[0]] if len(points) == 4 else []) for value in point),
                                    fill='#00ff88', width=2)
        for name, (x, y) in zip(CORNER_LABELS, points):
            self.canvas.create_oval(x - 5, y - 5, x + 5, y + 5, fill='#00ff88')
            self.canvas.create_text(x + 15, y - 15, text=name.upper(), fill='#ffffff', font=('Arial', 14, 'bold'))
        name = self.names[self.index]
        count = sum(sample['status'] != 'unlabeled' for sample in self.manifest['images'].values())
        self.status.set(f'{self.index + 1}/{len(self.names)}: {name} | saved {count}/{len(self.names)} | '
                        f"{self.manifest['images'][name]['status']} | points {len(points)}/4")

    def save_document(self):
        sample = dict(self.manifest['images'][self.names[self.index]])
        sample.update(status='document', quad={name: dict(x=x / self.image.width, y=y / self.image.height)
                                              for name, (x, y) in zip(CORNERS, self.points)})
        try:
            validate_sample(sample)
        except ValueError as exc:
            self.messagebox.showerror('Check corners', str(exc)); return
        self.save(sample)

    def save_no_document(self):
        sample = dict(self.manifest['images'][self.names[self.index]])
        sample.update(status='no-document', quad=None)
        self.save(sample)

    def save(self, sample):
        name = self.names[self.index]
        self.manifest['images'][name] = sample
        save_manifest(self.annotations, self.manifest)
        write_sample(Path(self.manifest['imageRoot']) / name, sample)
        self.dirty = False
        self.index = (self.index + 1) % len(self.names)
        self.load()

    def run(self):
        self.root.mainloop()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', nargs='?', default='label', choices=['init', 'label', 'status', 'export'])
    parser.add_argument('--images', type=Path, default=DEFAULT_IMAGES)
    parser.add_argument('--annotations', type=Path, default=DEFAULT_ANNOTATIONS)
    parser.add_argument('--output', type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    try:
        if args.action == 'export':
            print(json.dumps(export_labels(args.annotations, args.output), indent=2))
        elif args.action == 'label':
            Labeler(args.images, args.annotations).run()
        else:
            manifest = initialize(args.images, args.annotations)
            print(json.dumps({status: sum(sample['status'] == status for sample in manifest['images'].values())
                              for status in ('unlabeled', 'document', 'no-document')}, indent=2))
    except (ValueError, OSError) as exc:
        parser.exit(1, f'{exc}\n')


if __name__ == '__main__':
    main()
