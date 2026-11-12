import { createHash } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));
const id = name => createHash('sha256').update(`meshgram-ios:${name}`).digest('hex').slice(0, 24).toUpperCase();
const objects = [];
const add = (name, contents) => { objects.push(`${id(name)} = { ${contents} };`); return id(name); };
const quote = JSON.stringify;
const sources = ['MeshGramApp.swift', 'NearbyScanner.swift', 'IdentityStore.swift', 'ContactKeychainStore.swift'];
const files = sources.map(name => add(`file:${name}`, `isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = ${quote(name)}; sourceTree = "<group>";`));
const builds = sources.map(name => add(`build:${name}`, `isa = PBXBuildFile; fileRef = ${id(`file:${name}`)};`));
const product = add('product', 'isa = PBXFileReference; explicitFileType = wrapper.application; path = MeshGram.app; sourceTree = BUILT_PRODUCTS_DIR;');
const app = add('app', `isa = PBXGroup; children = (${files.join(',')},); path = App; sourceTree = "<group>";`);
const products = add('products', `isa = PBXGroup; children = (${product},); name = Products; sourceTree = "<group>";`);
const group = add('group', `isa = PBXGroup; children = (${app},${products},); sourceTree = "<group>";`);
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
const project = add('project', `isa = PBXProject; attributes = { BuildIndependentTargetsInParallel = YES; LastUpgradeCheck = 1500; TargetAttributes = { ${target} = { CreatedOnToolsVersion = 15.0; ProvisioningStyle = Automatic; }; }; }; buildConfigurationList = ${projectList}; compatibilityVersion = "Xcode 14.0"; developmentRegion = ru; hasScannedForEncodings = 0; knownRegions = (ru,Base,); mainGroup = ${group}; packageReferences = (${local},); productRefGroup = ${products}; projectDirPath = ""; projectRoot = ""; targets = (${target},);`);
const folder = join(root, 'MeshGram.xcodeproj');
mkdirSync(join(folder, 'xcshareddata', 'xcschemes'), { recursive: true });
writeFileSync(join(folder, 'project.pbxproj'), `// !$*UTF8*$!\n{ archiveVersion = 1; classes = {}; objectVersion = 56; objects = {\n${objects.join('\n')}\n}; rootObject = ${project}; }\n`);
writeFileSync(join(folder, 'xcshareddata/xcschemes/MeshGram.xcscheme'), `<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="1500" version="1.3">
  <BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries><BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES"><BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="${target}" BuildableName="MeshGram.app" BlueprintName="MeshGram" ReferencedContainer="container:MeshGram.xcodeproj"/></BuildActionEntry></BuildActionEntries></BuildAction>
  <LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0"><BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="${target}" BuildableName="MeshGram.app" BlueprintName="MeshGram" ReferencedContainer="container:MeshGram.xcodeproj"/></BuildableProductRunnable></LaunchAction>
  <AnalyzeAction buildConfiguration="Debug"/><ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>\n`);
process.stdout.write('Xcode project generated. This does not compile or sign the app.\n');
