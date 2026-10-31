#!/usr/bin/env bash
set -euo pipefail

# Run from the repository root after linkDebugFrameworkIosArm64.
# XcodeGen is needed only on the build machine, never on the tester's Mac.
xcodegen_binary=${1:?Pass the path to the pinned XcodeGen executable}
package_dir=build/iphone-test-package/OpenTabs-iPhone-Test
mkdir -p "$package_dir/Frameworks"
cp -R iosApp/Sources "$package_dir/"
cp -R core/build/bin/iosArm64/debugFramework/OpenTabsCore.framework "$package_dir/Frameworks/"
cp iosApp/device-project.yml "$package_dir/project.yml"
cp iosApp/IPHONE-TESTING.txt "$package_dir/START-HERE.txt"
git rev-parse HEAD > "$package_dir/SOURCE-REVISION.txt"
"$xcodegen_binary" generate --spec "$package_dir/project.yml"

# Verify the exact packaged project without signing or repository-local tools.
# Derived data lives outside the downloadable package.
xcodebuild build \
  -project "$package_dir/OpenTabs.xcodeproj" \
  -scheme OpenTabs \
  -configuration Debug \
  -destination 'generic/platform=iOS' \
  -derivedDataPath build/iphone-package-verification \
  CODE_SIGNING_ALLOWED=NO

# Preserve the framework and Xcode project's directory structure on download.
(cd build/iphone-test-package && ditto -c -k --keepParent OpenTabs-iPhone-Test OpenTabs-iPhone-Test.zip)
