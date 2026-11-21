// macOS 앱 번들에 ad-hoc 서명을 붙입니다 (Apple 개발자 인증서 없이).
// Apple Silicon(arm64)에서는 서명이 전혀 없는 앱이 실행되지 않으므로, dmg/zip 으로 묶기 전에 서명만 해 둡니다.
// 공증(notarization)은 하지 않으므로 처음 실행할 때 Gatekeeper 확인이 필요합니다(README 참고).
const { execFileSync } = require('node:child_process');
const path = require('node:path');

exports.default = async function adhocSign(context) {
  if (context.electronPlatformName !== 'darwin') return;
  const app = path.join(context.appOutDir, `${context.packager.appInfo.productFilename}.app`);
  console.log(`  • ad-hoc 서명: ${app}`);
  execFileSync('codesign', ['--force', '--deep', '--sign', '-', app], { stdio: 'inherit' });
  execFileSync('codesign', ['--verify', '--deep', '--strict', app], { stdio: 'inherit' });
};
