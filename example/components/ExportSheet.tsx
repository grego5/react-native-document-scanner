import { useEffect, useState } from 'react'
import { BackHandler, Text, TextInput, View } from 'react-native'
import type { CreatePdfOptions } from 'react-native-document-scanner'
import { colors, styles } from '../styles'
import { ChoiceRow } from './ChoiceRow'
import { PrimaryButton } from './PrimaryButton'

type Format = 'pdf' | 'images'

/** The PDF options an export passes on; the pages and output path are the caller's. */
export type PdfSettings = Omit<CreatePdfOptions, 'outputPath' | 'pages'>

const FORMATS = [
  { value: 'pdf', label: 'PDF' },
  { value: 'images', label: 'Images' },
] as const

/** A number typed into a field, or undefined when it is empty or not a positive number. */
function positive(text: string): number | undefined {
  const value = Number(text.replace(',', '.'))
  return text.trim() !== '' && Number.isFinite(value) && value > 0 ? value : undefined
}

function fraction(text: string): number | undefined {
  const value = Number(text.replace(',', '.'))
  return text.trim() !== '' && Number.isFinite(value) && value >= 0 && value <= 1 ? value : undefined
}

/** The overview's export choices: a shared PDF with its page size, resolution and quality, or the page images. */
export function ExportSheet({
  pageCount,
  onCancel,
  onSharePdf,
  onSaveImages,
}: {
  pageCount: number
  onCancel: () => void
  onSharePdf: (settings: PdfSettings) => void
  onSaveImages: () => void
}) {
  const [format, setFormat] = useState<Format>('pdf')
  // A4 in points.
  const [pageWidth, setPageWidth] = useState('595.28')
  const [pageHeight, setPageHeight] = useState('841.89')
  const [targetDpi, setTargetDpi] = useState('200')
  // Empty leaves the quality unset, so JPEGs that need no scaling are copied into the PDF unchanged.
  const [jpegQuality, setJpegQuality] = useState('')

  // Back closes the sheet before it reaches the overview.
  useEffect(() => {
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      onCancel()
      return true
    })
    return () => subscription.remove()
  }, [onCancel])

  const width = positive(pageWidth)
  const height = positive(pageHeight)
  const dpi = positive(targetDpi)
  const quality = jpegQuality.trim() === '' ? undefined : fraction(jpegQuality)
  const valid = width != null && height != null && dpi != null &&
    (jpegQuality.trim() === '' || quality != null)

  const field = (label: string, value: string, onChange: (text: string) => void, placeholder?: string) => (
    <View style={styles.exportField}>
      <Text style={styles.tuningLabel}>{label}</Text>
      <TextInput
        style={styles.numberInput}
        value={value}
        onChangeText={onChange}
        keyboardType="decimal-pad"
        placeholder={placeholder}
        placeholderTextColor={colors.border}
      />
    </View>
  )

  return (
    <View style={styles.optionsPanel}>
      <Text style={styles.sessionTitle}>Export {pageCount} page{pageCount === 1 ? '' : 's'}</Text>
      <ChoiceRow choices={FORMATS} value={format} onChange={setFormat} />
      {format === 'pdf' ? (
        <>
          <View style={styles.actionRow}>
            {field('Width (pt)', pageWidth, setPageWidth)}
            {field('Height (pt)', pageHeight, setPageHeight)}
          </View>
          <View style={styles.actionRow}>
            {field('Resolution (dpi)', targetDpi, setTargetDpi)}
            {field('JPEG quality (0-1)', jpegQuality, setJpegQuality, 'unchanged')}
          </View>
          <Text style={styles.diagnostics}>
            Images above the resolution are scaled down. Black & white pages keep 16 gray levels losslessly in PDF;
            JPEG quality applies to other pages.
          </Text>
        </>
      ) : (
        <Text style={styles.diagnostics}>
          Saves black & white pages as 4-bit grayscale PNGs (16 gray levels), and other pages as JPEGs.
        </Text>
      )}
      <View style={styles.actionRow}>
        <PrimaryButton label="Cancel" variant="secondary" style={styles.actionButton} onPress={onCancel} />
        {format === 'pdf' ? (
          <PrimaryButton
            label="Share PDF"
            style={styles.actionButton}
            disabled={!valid}
            onPress={() => onSharePdf({
              pageWidth: width,
              pageHeight: height,
              imageFit: 'contain',
              targetDpi: dpi,
              jpegQuality: quality,
            })}
          />
        ) : (
          <PrimaryButton label="Save to gallery" style={styles.actionButton} onPress={onSaveImages} />
        )}
      </View>
    </View>
  )
}
