const fs = require('node:fs');
const path = require('node:path');

const MODEL_ASSET = 'corner-detection-model.tflite';

module.exports = function withDocumentScanner(config, options = {}) {
  const { withDangerousMod, withPodfileProperties, withXcodeProject, IOSConfig } = require(
    require.resolve('expo/config-plugins', { paths: [config._internal.projectRoot] }),
  );

  config = withPodfileProperties(config, (config) => {
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

  if (options.cornerDetectionModel === undefined) return config;
  const source = (projectRoot) => {
    const file = path.resolve(projectRoot, options.cornerDetectionModel);
    if (!fs.statSync(file, { throwIfNoEntry: false })?.isFile()) {
      throw new Error(`react-native-document-scanner: cornerDetectionModel "${file}" is not a file.`);
    }
    return file;
  };

  config = withDangerousMod(config, ['android', (config) => {
    const assets = path.join(config.modRequest.platformProjectRoot, 'app', 'src', 'main', 'assets');
    fs.mkdirSync(assets, { recursive: true });
    fs.copyFileSync(source(config.modRequest.projectRoot), path.join(assets, MODEL_ASSET));
    return config;
  }]);

  return withXcodeProject(config, (config) => {
    const { projectName, platformProjectRoot, projectRoot } = config.modRequest;
    fs.copyFileSync(source(projectRoot), path.join(platformProjectRoot, projectName, MODEL_ASSET));
    const groupPath = config.modResults.pbxGroupByName(projectName)?.path?.replace(/^"|"$/g, '') ?? '';
    IOSConfig.XcodeUtils.addResourceFileToGroup({
      filepath: path.posix.relative(groupPath, path.posix.join(projectName, MODEL_ASSET)),
      groupName: projectName,
      isBuildFile: true,
      project: config.modResults,
    });
    return config;
  });
};
