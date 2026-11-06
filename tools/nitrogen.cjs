// Nitro 0.37.1 / Swift importer compatibility. See margelo/nitro#1186 and #1376.
// Patch an isolated generator copy; never change installed packages or generated output.
const fs = require('node:fs')
const path = require('node:path')
const { spawnSync } = require('node:child_process')
const root = path.resolve(__dirname, '..')
const installed = path.dirname(require.resolve('nitrogen/package.json'))
const metadata = JSON.parse(fs.readFileSync(path.join(installed, 'package.json'), 'utf8'))
if (metadata.version !== '0.37.1') throw new Error('Revalidate the Swift importer workaround before changing Nitrogen')
const isolated = path.join(root, '.local', 'nitrogen-0.37.1')
fs.mkdirSync(isolated, { recursive: true })
fs.copyFileSync(path.join(installed, 'package.json'), path.join(isolated, 'package.json'))
fs.cpSync(path.join(installed, 'lib'), path.join(isolated, 'lib'), { recursive: true })
const generator = path.join(isolated, 'lib', 'syntax', 'c++', 'CppStruct.js')
const original = fs.readFileSync(generator, 'utf8')
const before = 'equatableFunc = `friend bool operator==(const ${typename}& lhs, const ${typename}& rhs) = default;`;'
const after = 'equatableFunc = `#if !defined(DOCUMENT_SCANNER_SWIFT_IMPORT)\nfriend bool operator==(const ${typename}& lhs, const ${typename}& rhs) = default;\n#endif`;'
if (original.split(before).length !== 2) throw new Error('Unexpected Nitrogen equality generator; inspect before regenerating')
fs.writeFileSync(generator, original.replace(before, after))
const result = spawnSync(process.execPath, [path.join(isolated, 'lib', 'index.js'), ...process.argv.slice(2)], {
  cwd: root, stdio: 'inherit',
})
if (result.error) throw result.error
process.exitCode = result.status ?? 1
