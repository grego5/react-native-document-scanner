const path = require('node:path')
const { getDefaultConfig } = require('expo/metro-config')

const projectRoot = __dirname
const config = getDefaultConfig(projectRoot)

// Resolve the library and its Nitro peer from the example app installation.
config.resolver.disableHierarchicalLookup = true
config.resolver.nodeModulesPaths = [path.join(projectRoot, 'node_modules')]

module.exports = config
