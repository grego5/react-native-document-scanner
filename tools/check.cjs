// Routine checks only. Native compilation, code generation and device work have separate commands.
const { spawnSync } = require('node:child_process')
const path = require('node:path')

const root = path.resolve(__dirname, '..')
const compilerPackage = require.resolve('typescript/package.json')
const compilerBin = require(compilerPackage).bin
const compiler = path.resolve(path.dirname(compilerPackage), compilerBin.tsc ?? compilerBin.tsc6)
const checks = [
  ['Library typecheck', '--library', [compiler, '--noEmit', '-p', 'tsconfig.json']],
  ['Example typecheck', '--example', [compiler, '--noEmit', '-p', 'example/tsconfig.json']],
  ['JS contracts', '--contracts', ['--test', 'src/scanner-contracts.test.cjs']],
]

const args = process.argv.slice(2)
if (args.length > 1 || args.some((arg) => !['--list', '--help', ...checks.map((check) => check[1])].includes(arg))) {
  console.error('Usage: npm run check [-- --list|--library|--example|--contracts]')
  process.exitCode = 1
} else if (args[0] === '--list' || args[0] === '--help') {
  for (const [name] of checks) console.log(name)
  console.log('No native compilation, generation, installation or device checks.')
} else {
  for (const [name, flag, command] of checks) {
    if (args.length === 1 && args[0] !== flag) continue
    console.log(`\n${name}`)
    // Direct argument arrays preserve paths on Windows and avoid shell interpretation.
    const result = spawnSync(process.execPath, command, { cwd: root, stdio: 'inherit' })
    if (result.error || result.status !== 0) {
      if (result.error) console.error(result.error.message)
      if (result.signal) console.error(`${name} stopped by ${result.signal}`)
      process.exitCode = result.status ?? 1
      break
    }
  }
}
