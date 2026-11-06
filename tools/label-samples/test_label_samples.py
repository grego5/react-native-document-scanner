"""Metadata and Windows-path tests; all image fixtures are temporary and synthetic."""
import json
import os
from pathlib import Path
import tempfile
import sys
import unittest

from PIL import Image
from label_samples import Labeler, CORNERS, export_labels, initialize, save_manifest, validate_sample, write_sample


class MetadataTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=os.environ.get('LABEL_SAMPLES_TEST_TEMP'))
        self.root = Path(self.temp.name)
        self.photos = self.root / 'photos with spaces'
        self.photos.mkdir()
        for name in ('page.jpg', 'other.png'):
            Image.new('RGB', (80, 120), 'white').save(self.photos / name)
        self.annotations = self.root / 'metadata' / 'annotations.json'
        self.manifest = initialize(self.photos, self.annotations)

    def tearDown(self):
        self.temp.cleanup()

    def document(self):
        return dict(self.manifest['images']['page.jpg'], status='document', quad={
            name: dict(x=x, y=y) for name, (x, y) in zip(CORNERS, ((.1,.2),(.9,.2),(.9,.8),(.1,.8)))
        })

    @unittest.skipUnless(sys.platform == 'win32', 'Windows Tk integration check')
    def test_window_loads_and_renders_images_from_scanner_metadata(self):
        app = Labeler(self.photos, self.annotations)
        try:
            app.root.withdraw()
            app.root.update_idletasks()
            app.load()
            self.assertEqual((80,120), app.image.size)
            self.assertEqual([], app.points)
            self.assertTrue(hasattr(app, 'photo'))
            self.assertTrue(any(app.canvas.type(item) == 'image' for item in app.canvas.find_all()))
            app.navigate(1)
            self.assertEqual((80,120), app.image.size)
            # Save/reopen only synthetic fixture corners; real sample photos remain unlabeled.
            app.points = [(8,24),(72,24),(72,96),(8,96)]
            name = app.names[app.index]
            app.save_document()
            reopened = initialize(self.photos, self.annotations)
            self.assertEqual(dict(x=.1,y=.2), reopened['images'][name]['quad']['topLeft'])
        finally:
            app.root.destroy()

    def test_initialization_writes_only_metadata_and_preserves_progress(self):
        self.assertEqual(2, self.manifest['version'])
        self.assertEqual('exifUprightNormalized', self.manifest['coordinateSpace'])
        self.assertTrue(all(s['status'] == 'unlabeled' and s['quad'] is None for s in self.manifest['images'].values()))
        sample = self.document()
        self.assertEqual((self.photos / 'page.jpg').as_uri(), sample['originalUri'])
        self.manifest['images']['page.jpg'] = sample
        save_manifest(self.annotations, self.manifest)
        reopened = initialize(self.photos, self.annotations)
        self.assertEqual(sample, reopened['images']['page.jpg'])
        self.assertEqual([self.annotations], list(self.annotations.parent.iterdir()))
        self.assertNotIn(b'\r\n', self.annotations.read_bytes())

    def test_reviewed_export_has_native_document_quad_and_no_image_files(self):
        self.manifest['images']['page.jpg'] = self.document()
        save_manifest(self.annotations, self.manifest)
        output = self.root / 'export' / 'labels.json'
        result = export_labels(self.annotations, output)
        with output.open(encoding='utf-8') as f:
            exported = json.load(f)
        self.assertEqual(1, result['exported'])
        self.assertEqual(1, result['skippedUnlabeled'])
        self.assertEqual(['page.jpg'], list(exported['images']))
        quad = exported['images']['page.jpg']['quad']
        self.assertEqual(set(CORNERS), set(quad))
        self.assertEqual(dict(x=.1, y=.2), quad['topLeft'])
        self.assertEqual([output], list(output.parent.iterdir()))
        self.assertNotIn('bytes', exported['images']['page.jpg'])
        with self.assertRaises(FileExistsError):
            export_labels(self.annotations, output)

    def test_no_document_exports_null_quad(self):
        sample = dict(self.manifest['images']['page.jpg'], status='no-document', quad=None)
        validate_sample(sample)
        self.manifest['images']['page.jpg'] = sample
        save_manifest(self.annotations, self.manifest)
        output = self.root / 'labels.json'
        export_labels(self.annotations, output)
        with output.open(encoding='utf-8') as f:
            exported = json.load(f)
        self.assertIsNone(exported['images']['page.jpg']['quad'])

    def test_invalid_geometry_and_missing_corners_are_rejected(self):
        sample = self.document()
        validate_sample(sample)
        for value in (float('nan'), 1.01, None):
            broken = self.document()
            broken['quad']['topLeft']['x'] = value
            with self.assertRaises(ValueError):
                validate_sample(broken)
        sample['quad']['topLeft'], sample['quad']['topRight'] = sample['quad']['topRight'], sample['quad']['topLeft']
        with self.assertRaises(ValueError):
            validate_sample(sample)
        sample = self.document()
        del sample['quad']['bottomLeft']
        with self.assertRaises(ValueError):
            validate_sample(sample)

    def test_changed_source_cannot_reuse_or_export_old_labels(self):
        self.manifest['images']['page.jpg'] = self.document()
        save_manifest(self.annotations, self.manifest)
        Image.new('RGB', (80,120), 'black').save(self.photos / 'page.jpg')
        with self.assertRaisesRegex(ValueError, 'Image changed'):
            initialize(self.photos, self.annotations)
        with self.assertRaisesRegex(ValueError, 'Image changed'):
            export_labels(self.annotations, self.root / 'labels.json')
        self.assertFalse((self.root / 'labels.json').exists())

    def test_scanner_samples_seed_labels_and_receive_corrections(self):
        import hashlib
        quad = self.document()['quad']
        for name, status in (('1793396103268-640.png', 'unlabeled'), ('1793396103268-4000.jpg', 'document')):
            Image.new('RGB', (60, 80), 'white').save(self.photos / name)
            digest = hashlib.sha256((self.photos / name).read_bytes()).hexdigest()
            save_manifest((self.photos / name).with_suffix('.json'), dict(
                version=2, coordinateSpace='exifUprightNormalized', image=name, sha256=digest, status=status, quad=quad,
                prediction=dict(quad=quad, score=.9)))
        manifest = initialize(self.photos, self.annotations)
        self.assertEqual(('unlabeled', quad), (manifest['images']['1793396103268-640.png']['status'],
                                               manifest['images']['1793396103268-640.png']['quad']))
        self.assertEqual('document', manifest['images']['1793396103268-4000.jpg']['status'])
        self.assertNotIn('1793396103268-640.json', manifest['images'])
        write_sample(self.photos / '1793396103268-640.png', dict(status='no-document', quad=None))
        saved = json.loads((self.photos / '1793396103268-640.json').read_text(encoding='utf-8'))
        self.assertEqual(('no-document', None, .9), (saved['status'], saved['quad'], saved['prediction']['score']))
        Image.new('RGB', (60, 80), 'black').save(self.photos / '1793396103268-4000.jpg')
        with self.assertRaisesRegex(ValueError, 'Image changed'):
            initialize(self.photos, self.root / 'fresh.json')

    def test_exif_dimensions_and_empty_export(self):
        exif = Image.Exif()
        exif[274] = 6
        Image.new('RGB', (100,60), 'white').save(self.photos / 'rotated.jpg', exif=exif)
        manifest = initialize(self.photos, self.annotations)
        self.assertEqual((60,100), (manifest['images']['rotated.jpg']['sourceWidth'], manifest['images']['rotated.jpg']['sourceHeight']))
        with self.assertRaisesRegex(ValueError, 'No reviewed'):
            export_labels(self.annotations, self.root / 'labels.json')


if __name__ == '__main__':
    unittest.main()
