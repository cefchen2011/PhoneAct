/**
 * PhoneAct MCP 最小客户端（供脚本化验证使用）。
 *
 * 端点与令牌一律从环境变量读取，仓库中不保存任何凭据：
 *   PHONEACT_URL   默认 http://127.0.0.1:8517/mcp
 *   PHONEACT_TOKEN 可选；回环地址通常不需要，局域网访问必填
 */

export const BASE = process.env.PHONEACT_URL || 'http://127.0.0.1:8517/mcp';
const TOKEN = process.env.PHONEACT_TOKEN || '';

let id = 0;

function headers() {
  const h = { 'Content-Type': 'application/json' };
  if (TOKEN) h['Authorization'] = 'Bearer ' + TOKEN;
  return h;
}

export async function rpc(method, params) {
  const res = await fetch(BASE, {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify({ jsonrpc: '2.0', id: ++id, method, params: params || {} }),
  });
  return JSON.parse(await res.text());
}

export async function call(name, args) {
  const r = await rpc('tools/call', { name, arguments: args || {} });
  if (r.error) return 'RPC ERR ' + JSON.stringify(r.error);
  const c = r.result.content[0];
  return c.text !== undefined ? c.text : '(image)';
}

export async function rec() {
  return JSON.parse(await call('screen_recognize', { force: true }));
}

export async function tap(x, y) { return call('ui_tap', { x, y }); }

export async function tapText(text, opts) {
  return call('ui_tap_text', Object.assign({ text }, opts || {}));
}

export async function sleep(ms) { return new Promise((r) => setTimeout(r, ms)); }

export function texts(model) {
  return model.elements.filter((e) => e.text).map((e) => ({ t: e.text, c: e.center, d: e.colorDelta }));
}

/** 把无障碍节点树拍平成数组。 */
export function flatten(node, out) {
  out = out || [];
  if (!node) return out;
  out.push(node);
  (node.children || []).forEach((c) => flatten(c, out));
  return out;
}
