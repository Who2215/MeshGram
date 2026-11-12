import { createHash } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));
const id = name => createHash('sha256').update(`meshgram-ios:${name}`).digest('hex').slice(0, 24).toUpperCase();
const objects = [];
const add = (name, contents) => { objects.push(`${id(name)} = { ${contents} };`); return id(name); };
const quote = JSON.stringify;
const sources = ['MeshGramApp.swift', 'NearbyScanner.swift', 'IdentityStore.swift', 'ContactKeychainStore.swift', 'FriendshipPane.swift'];
const files = sources.map(name => add(`file:${name}`, `isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = ${quote(name)}; sourceTree = "<group>";`));
const builds = sources.map(name => add(`build:${name}`, `isa = PBXBuildFile; fileRef = ${id(`file:${name}`)};`));
const product = add('product', 'isa = PBXFileReference; explicitFileType = wrapper.application; path = MeshGram.app; sourceTree = BUILT_PRODUCTS_DIR;');
const uiFile = add('uiFile', 'isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = FriendshipUITests.swift; sourceTree = "<group>";');
const uiBuild = add('uiBuild', `isa = PBXBuildFile; fileRef = ${uiFile};`);
const uiProduct = add('uiProduct', 'isa = PBXFileReference; explicitFileType = wrapper.cfbundle; path = MeshGramUITests.xctest; sourceTree = BUILT_PRODUCTS_DIR;');
const app = add('app', `isa = PBXGroup; children = (${files.join(',')},); path = App; sourceTree = "<group>";`);
const uiGroup = add('uiGroup', `isa = PBXGroup; children = (${uiFile},); path = UITests; sourceTree = "<group>";`);
const products = add('products', `isa = PBXGroup; children = (${product},${uiProduct},); name = Products; sourceTree = "<group>";`);
const group = add('group', `isa = PBXGroup; children = (${app},${uiGroup},${products},); sourceTree = "<group>";`);
const local = add('local', 'isa = XCLocalSwiftPackageReference; relativePath = ".";');
const core = add('core', `isa = XCSwiftPackageProductDependency; package = ${local}; productName = MeshGramCore;`);
const coreBuild = add('coreBuild', `isa = PBXBuildFile; productRef = ${core};`);
const sourcePhase = add('sources', `isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; files = (${builds.join(',')},); runOnlyForDeploymentPostprocessing = 0;`);
const framework = add('framework', `isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = (${coreBuild},); runOnlyForDeploymentPostprocessing = 0;`);
const resources = add('resources', 'isa = PBXResourcesBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0;');
const projectConfigs = [], targetConfigs = [];
for (const name of ['Debug', 'Release']) {
  projectConfigs.push(add(`project:${name}`, `isa = XCBuildConfiguration; name = ${name}; buildSettings = { CLANG_ENABLE_MODULES = YES; SDKROOT = iphoneos; IPHONEOS_DEPLOYMENT_TARGET = 16.0; SWIFT_VERSION = 5.0; };`));
  targetConfigs.push(add(`target:${name}`, `isa = XCBuildConfiguration; name = ${name}; buildSettings = { CODE_SIGN_STYLE = Automatic; GENERATE_INFOPLIST_FILE = NO; INFOPLIST_FILE = App/Info.plist; PRODUCT_BUNDLE_IDENTIFIER = com.meshgram.ios; PRODUCT_NAME = "$(TARGET_NAME)"; SUPPORTED_PLATFORMS = "iphoneos iphonesimulator"; TARGETED_DEVICE_FAMILY = 1; SWIFT_VERSION = 5.0; SWIFT_OPTIMIZATION_LEVEL = ${quote(name === 'Debug' ? '-Onone' : '-O')}; };`));
}
const projectList = add('projectConfigs', `isa = XCConfigurationList; buildConfigurations = (${projectConfigs.join(',')},); defaultConfigurationIsVisible = 0; defaultConfigurationName = Release;`);
const targetList = add('targetConfigs', `isa = XCConfigurationList; buildConfigurations = (${targetConfigs.join(',')},); defaultConfigurationIsVisible = 0; defaultConfigurationName = Release;`);
const target = add('target', `isa = PBXNativeTarget; buildConfigurationList = ${targetList}; buildPhases = (${sourcePhase},${framework},${resources},); buildRules = (); dependencies = (); name = MeshGram; packageProductDependencies = (${core},); productName = MeshGram; productReference = ${product}; productType = "com.apple.product-type.application";`);
const uiSources = add('uiSources', `isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; files = (${uiBuild},); runOnlyForDeploymentPostprocessing = 0;`);
const uiFrameworks = add('uiFrameworks', 'isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0;');
const uiResources = add('uiResources', 'isa = PBXResourcesBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0;');
const uiConfigs = ['Debug', 'Release'].map(name => add(`ui:${name}`, `isa = XCBuildConfiguration; name = ${name}; buildSettings = { CODE_SIGN_STYLE = Automatic; GENERATE_INFOPLIST_FILE = YES; PRODUCT_BUNDLE_IDENTIFIER = com.meshgram.ios.uitests; PRODUCT_NAME = "$(TARGET_NAME)"; TARGETED_DEVICE_FAMILY = 1; SWIFT_VERSION = 5.0; SWIFT_OPTIMIZATION_LEVEL = ${quote(name === 'Debug' ? '-Onone' : '-O')}; TEST_TARGET_NAME = MeshGram; ENABLE_TESTING_SEARCH_PATHS = YES; ALWAYS_EMBED_SWIFT_STANDARD_LIBRARIES = YES; LD_RUNPATH_SEARCH_PATHS = "$(inherited) @executable_path/Frameworks @loader_path/Frameworks"; };`));
const uiList = add('uiConfigs', `isa = XCConfigurationList; buildConfigurations = (${uiConfigs.join(',')},); defaultConfigurationIsVisible = 0; defaultConfigurationName = Release;`);
const uiProxy = add('uiProxy', `isa = PBXContainerItemProxy; containerPortal = ${id('project')}; proxyType = 1; remoteGlobalIDString = ${target}; remoteInfo = MeshGram;`);
const uiDependency = add('uiDependency', `isa = PBXTargetDependency; target = ${target}; targetProxy = ${uiProxy};`);
const uiTarget = add('uiTarget', `isa = PBXNativeTarget; buildConfigurationList = ${uiList}; buildPhases = (${uiSources},${uiFrameworks},${uiResources},); buildRules = (); dependencies = (${uiDependency},); name = MeshGramUITests; packageProductDependencies = (); productName = MeshGramUITests; productReference = ${uiProduct}; productType = "com.apple.product-type.bundle.ui-testing";`);
const project = add('project', `isa = PBXProject; attributes = { BuildIndependentTargetsInParallel = YES; LastUpgradeCheck = 1500; TargetAttributes = { ${target} = { CreatedOnToolsVersion = 15.0; ProvisioningStyle = Automatic; }; ${uiTarget} = { CreatedOnToolsVersion = 16.0; TestTargetID = ${target}; }; }; }; buildConfigurationList = ${projectList}; compatibilityVersion = "Xcode 14.0"; developmentRegion = ru; hasScannedForEncodings = 0; knownRegions = (ru,Base,); mainGroup = ${group}; packageReferences = (${local},); productRefGroup = ${products}; projectDirPath = ""; projectRoot = ""; targets = (${target},${uiTarget},);`);
const folder = join(root, 'MeshGram.xcodeproj');
mkdirSync(join(folder, 'xcshareddata', 'xcschemes'), { recursive: true });
writeFileSync(join(folder, 'project.pbxproj'), `// !$*UTF8*$!\n{ archiveVersion = 1; classes = {}; objectVersion = 56; objects = {\n${objects.join('\n')}\n}; rootObject = ${project}; }\n`);
writeFileSync(join(folder, 'xcshareddata/xcschemes/MeshGram.xcscheme'), `<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="1500" version="1.3">
  <BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries><BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES"><BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="${target}" BuildableName="MeshGram.app" BlueprintName="MeshGram" ReferencedContainer="container:MeshGram.xcodeproj"/></BuildActionEntry></BuildActionEntries></BuildAction>
  <LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0"><BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="${target}" BuildableName="MeshGram.app" BlueprintName="MeshGram" ReferencedContainer="container:MeshGram.xcodeproj"/></BuildableProductRunnable></LaunchAction>
  <AnalyzeAction buildConfiguration="Debug"/><ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>\n`);
const appRef = `<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="${target}" BuildableName="MeshGram.app" BlueprintName="MeshGram" ReferencedContainer="container:MeshGram.xcodeproj"/>`;
const uiRef = `<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="${uiTarget}" BuildableName="MeshGramUITests.xctest" BlueprintName="MeshGramUITests" ReferencedContainer="container:MeshGram.xcodeproj"/>`;
writeFileSync(join(folder, 'xcshareddata', 'xcschemes', 'MeshGramUI.xcscheme'), `<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="1600" version="1.3">
  <BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries>
    <BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="NO" buildForArchiving="NO" buildForAnalyzing="YES">${appRef}</BuildActionEntry>
    <BuildActionEntry buildForTesting="YES" buildForRunning="NO" buildForProfiling="NO" buildForArchiving="NO" buildForAnalyzing="NO">${uiRef}</BuildActionEntry>
  </BuildActionEntries></BuildAction>
  <TestAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv="YES"><Testables><TestableReference skipped="NO">${uiRef}</TestableReference></Testables><MacroExpansion>${appRef}</MacroExpansion></TestAction>
</Scheme>\n`);
process.stdout.write('Xcode project generated. This does not compile or sign the app.\n');
