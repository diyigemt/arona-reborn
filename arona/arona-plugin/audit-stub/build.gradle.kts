plugins {
  id("arona-plugin")
}

// 仅供 sandbox 联调: 用标记词控制 ContentAuditEvent 的结果, 替代真实 COS 审核. 严禁部署到生产.
arona {
  id = "com.diyigemt.arona.audit.stub"
  name = "audit-stub"
  author = "diyigemt"
  version = "0.0.1"
  description = "sandbox 专用内容审核桩"
  mainClass = "com.diyigemt.arona.audit.stub.PluginMain"
}
