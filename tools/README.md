# tools/

脚本化验证与调试用的小工具。**不包含任何凭据**，端点与令牌都从环境变量读取。

```bash
# 1) 把手机的 MCP 端口转发到本机
adb forward tcp:8517 tcp:8517

# 2) 跑端到端自检
node tools/verify.mjs

# 局域网直连（需带令牌，令牌在 App 的「MCP」页可复制）
PHONEACT_URL=http://<手机IP>:8517/mcp PHONEACT_TOKEN=<token> node tools/verify.mjs
```

| 文件 | 说明 |
| --- | --- |
| `pa.mjs` | MCP 最小客户端：`rpc / call / rec / tap / tapText / flatten` |
| `verify.mjs` | 端到端自检：握手、工具清单、三条通道、分块识别、坐标、截屏、点击/滑动、root shell、应用列表、节点树 |
