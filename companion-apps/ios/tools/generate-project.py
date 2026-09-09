#!/usr/bin/env python3
"""Generate a dependency-free Xcode project; IDs are deterministic."""
from pathlib import Path
import hashlib, json
root=Path(__file__).resolve().parents[1]
def uid(s): return hashlib.sha256(s.encode()).hexdigest()[:24].upper()
files=sorted([*root.glob('App/*.swift'),*root.glob('Sources/CompanionCore/*.swift')])
resources=[root/'App/sample-intent.json', *sorted(root.glob('App/AppIcon*.png'))]
objects=[]
def obj(name, body): objects.append(f'{uid(name)} = {{ {body} }};'); return uid(name)
refs=[]; sources=[]; res=[]
signing=obj('refConfig/Signing.xcconfig', 'isa = PBXFileReference; lastKnownFileType = text.xcconfig; path = "Config/Signing.xcconfig"; sourceTree = "<group>";')
refs.append(signing)
for f in files+resources:
 p=str(f.relative_to(root)); kind='sourcecode.swift' if f.suffix=='.swift' else 'folder.assetcatalog' if f.suffix=='.xcassets' else 'image.png' if f.suffix=='.png' else 'text.json'
 ref=obj('ref'+p,f'isa = PBXFileReference; lastKnownFileType = {kind}; path = "{p}"; sourceTree = "<group>";'); refs.append(ref)
 build=obj('build'+p,f'isa = PBXBuildFile; fileRef = {ref};')
 (sources if f.suffix=='.swift' else res).append(build)
product=obj('product','isa = PBXFileReference; explicitFileType = wrapper.application; path = YanoCompanion.app; sourceTree = BUILT_PRODUCTS_DIR;')
products=obj('products',f'isa = PBXGroup; children = ({product}); name = Products; sourceTree = "<group>";')
group=obj('group',f'isa = PBXGroup; children = ({",".join(refs+[products])}); sourceTree = "<group>";')
src=obj('sources',f'isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; files = ({",".join(sources)}); runOnlyForDeploymentPostprocessing = 0;')
resphase=obj('resources',f'isa = PBXResourcesBuildPhase; buildActionMask = 2147483647; files = ({",".join(res)}); runOnlyForDeploymentPostprocessing = 0;')
frameworks=obj('frameworks','isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0;')
configs={}
for scope in ['project','target']:
 ids=[]
 for mode in ['Debug','Release']:
  settings={'CLANG_ENABLE_MODULES':'YES','SWIFT_VERSION':'5.0','IPHONEOS_DEPLOYMENT_TARGET':'17.0','SDKROOT':'iphoneos','SWIFT_OPTIMIZATION_LEVEL':'-Onone' if mode=='Debug' else '-O','DEBUG_INFORMATION_FORMAT':'dwarf'}
  if scope=='target': settings.update({'PRODUCT_BUNDLE_IDENTIFIER':'com.bloxbean.yano.companion','PRODUCT_NAME':'YanoCompanion','INFOPLIST_FILE':'App/Info.plist','CODE_SIGN_STYLE':'Automatic','TARGETED_DEVICE_FAMILY':'1','MARKETING_VERSION':'0.1.0','CURRENT_PROJECT_VERSION':'1','SWIFT_EMIT_LOC_STRINGS':'YES','SUPPORTED_PLATFORMS':'iphoneos iphonesimulator','SUPPORTS_MACCATALYST':'NO'})
  if mode=='Debug': settings['SWIFT_ACTIVE_COMPILATION_CONDITIONS']='DEBUG'
  body=' '.join(k+' = '+json.dumps(v)+';' for k,v in settings.items())
  base=f'baseConfigurationReference = {signing};' if scope=='target' else ''
  ids.append(obj(scope+mode,f'isa = XCBuildConfiguration; {base} buildSettings = {{ {body} }}; name = {mode};'))
 configs[scope]=obj(scope+'configs',f'isa = XCConfigurationList; buildConfigurations = ({",".join(ids)}); defaultConfigurationIsVisible = 0; defaultConfigurationName = Release;')
target=obj('target',f'isa = PBXNativeTarget; buildConfigurationList = {configs["target"]}; buildPhases = ({src},{frameworks},{resphase}); buildRules = (); dependencies = (); name = YanoCompanion; productName = YanoCompanion; productReference = {product}; productType = "com.apple.product-type.application";')
project=obj('project',f'isa = PBXProject; attributes = {{ LastUpgradeCheck = 2630; }}; buildConfigurationList = {configs["project"]}; compatibilityVersion = "Xcode 14.0"; developmentRegion = en; hasScannedForEncodings = 0; knownRegions = (en,Base); mainGroup = {group}; productRefGroup = {products}; projectDirPath = ""; projectRoot = ""; targets = ({target});')
(root/'YanoCompanion.xcodeproj/project.pbxproj').write_text('// !$*UTF8*$!\n{ archiveVersion = 1; classes = {}; objectVersion = 56; objects = {\n'+'\n'.join(objects)+f'\n}}; rootObject = {project}; }}\n')
(root/'YanoCompanion.xcodeproj/xcshareddata/xcschemes/YanoCompanion.xcscheme').write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="2630" version="1.3"><BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries><BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES"><BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{target}" BuildableName="YanoCompanion.app" BlueprintName="YanoCompanion" ReferencedContainer="container:YanoCompanion.xcodeproj"/></BuildActionEntry></BuildActionEntries></BuildAction><LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES" debugServiceExtension="internal" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0"><BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{target}" BuildableName="YanoCompanion.app" BlueprintName="YanoCompanion" ReferencedContainer="container:YanoCompanion.xcodeproj"/></BuildableProductRunnable></LaunchAction><ProfileAction buildConfiguration="Release"/><AnalyzeAction buildConfiguration="Debug"/><ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/></Scheme>''')
