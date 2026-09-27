/**
 * PhoneAct 端到端自检。
 *
 *   PHONEACT_URL=http://127.0.0.1:8517/mcp node tools/verify.mjs
 *
 * 建议先做端口转发：adb forward tcp:8517 tcp:8517
 */
import { call, rpc } from './pa.mjs';

const ok = [], bad = [];
const chk = (name, cond, info) => (cond ? ok : bad).push(name + (info ? ' (' + info + ')' : ''));

(async () => {
  const init = await rpc('initialize', {
    protocolVersion: '2025-06-18', capabilities: {},
    clientInfo: { name: 'phoneact-verify', version: '1' },
  });
  chk('MCP initialize', init.result && init.result.protocolVersion === '2025-06-18',
    init.result && init.result.protocolVersion);

  const tl = await rpc('tools/list', {});
  const tools = (tl.result && tl.result.tools) || [];
  chk('tools/list', tools.length >= 20, tools.length + ' tools');

  const st = JSON.parse(await call('device_status', {}));
  chk('Root 通道', st.root.available === true, st.root.path);
  chk('无障碍通道', st.accessibility.connected === true);
  chk('Xposed 模块', st.xposed.active === true && st.xposed.hooks.length >= 2,
    'bridge=' + st.xposed.bridgeVersion + ' hooks=' + st.xposed.hooks.length);
  chk('OCR 引擎', st.ocr.available === true);

  const m = JSON.parse(await call('screen_recognize', { force: true }));
  const ids = m.blocks.map((b) => b.id);
  chk('分块识别', ids.length >= 1 && ids.includes('main'), ids.join('/'));
  const withText = m.elements.filter((e) => e.text);
  chk('颜色+文字元素', withText.length > 5, withText.length + ' 个');
  chk('输出屏幕坐标', withText.every((e) => Array.isArray(e.center) && e.center.length === 2));
  chk('颜色不一致度', withText.every((e) => typeof e.colorDelta === 'number'),
    withText.length ? 'min=' + Math.min(...withText.map((e) => e.colorDelta)) : '-');

  const shot = await call('screen_screenshot', { format: 'info' });
  chk('截屏', shot.includes('"width"'), shot.slice(0, 80));

  chk('ui_tap', (await call('ui_tap', { x: 640, y: 1200 })).includes('"ok":true'));
  chk('ui_swipe', (await call('ui_swipe', { x1: 640, y1: 1600, x2: 640, y2: 1100, duration_ms: 250 })).includes('"ok":true'));
  chk('ui_global home', (await call('ui_global', { action: 'home' })).includes('"ok":true'));

  const sh = JSON.parse(await call('shell_exec', { command: 'id -u' }));
  chk('shell_exec(root)', sh.exitCode === 0 && sh.stdout.trim() === '0', 'uid=' + sh.stdout.trim());

  const apps = JSON.parse(await call('app_list', { limit: 5 }));
  chk('app_list', apps.count > 0, apps.count + ' 个');

  const tree = JSON.parse(await call('ui_dump_tree', { depth: 8 }));
  chk('ui_dump_tree', !!tree && !!tree.className);

  console.log('通过 ' + ok.length + '/' + (ok.length + bad.length));
  ok.forEach((o) => console.log('  ✓ ' + o));
  bad.forEach((o) => console.log('  ✗ ' + o));
  process.exit(bad.length ? 1 : 0);
})().catch((e) => { console.error('FAIL', e); process.exit(2); });
