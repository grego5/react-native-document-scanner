const path = require('node:path');

module.exports = function withDocumentScanner(config) {
  const { withPodfileProperties } = require(
    require.resolve('expo/config-plugins', { paths: [config._internal.projectRoot] }),
  );

  return withPodfileProperties(config, (config) => {
    const key = 'apple.extraPods';
    const name = 'DocumentScannerOpenCV';
    const extraPods = JSON.parse(config.modResults[key] ?? '[]');
    const podspec = path
      .relative(config.modRequest.platformProjectRoot, path.join(__dirname, 'ios', `${name}.podspec`))
      .split(path.sep)
      .join('/');

    config.modResults[key] = JSON.stringify([
      ...extraPods.filter((pod) => pod.name !== name),
      { name, podspec },
    ]);
    return config;
  });
};
