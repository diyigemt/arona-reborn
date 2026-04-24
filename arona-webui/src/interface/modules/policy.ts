// {"id":"1","name":"1","rules":[{"effect":"ALLOW","groupType":"ALL","children":[{"effect":"ALLOW","groupType":"ALL","rule":{"type":"Resource","operator":"IsCHILD","key":"id","value":"com.diyigemt.arona:*"}},{"effect":"ALLOW","groupType":"ALL","children":[{"effect":"ALLOW","groupType":"ALL","rule":{"type":"Subject","operator":"Equal","key":"type","value":"member"}},{"effect":"ALLOW","groupType":"ALL","rule":{"type":"Environment","operator":"GreaterThan","key":"time","value":"16:00"}}]}]}]}
export type PolicyRootEffect = "ALLOW" | "DENY";
export type PolicyNodeGroupType = "ALL" | "ANY" | "NOT_ALL" | "NOT_ANY";
export type PolicyRuleType = "Resource" | "Action" | "Subject" | "Environment";
export type PolicyRuleOperator =
  | "Equal"
  | "LessThan"
  | "GreaterThan"
  | "LessThanEqual"
  | "GreaterThanEqual"
  | "Contains"
  | "ContainsAll"
  | "ContainsAny"
  | "IsIn"
  | "IsChild";
export interface Policy {
  id: string;
  name: string;
  effect: PolicyRootEffect;
  rules: PolicyNode[];
}
export interface PolicyNode {
  groupType: PolicyNodeGroupType;
  rule?: PolicyRule[];
  children?: PolicyNode[];
}
export interface PolicyRule {
  type: PolicyRuleType;
  operator: PolicyRuleOperator;
  key: string;
  value: string;
}
export type PolicyResource = string;

// --- 策略预览 API (POST /policy/preview) 的请求/响应结构 ---
// 前端在策略编辑器上的 "测试" 按钮通过该 API 调后端 evaluator 拿 allow/deny 判定.
export type PolicyPreviewDecision = "allow" | "deny";

export interface PolicyPreviewReq {
  policies: Policy[];
  subject: Record<string, string>;
  action: Record<string, string>;
  resource: Record<string, string>;
  environment: Record<string, string>;
}

export interface PolicyPreviewResp {
  decision: PolicyPreviewDecision;
  hitPolicyId: string | null;
  reason: string;
}
