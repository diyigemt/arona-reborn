// {"id":"1","name":"1","rules":[{"effect":"ALLOW","groupType":"ALL","children":[{"effect":"ALLOW","groupType":"ALL","rule":{"type":"Resource","operator":"IsCHILD","key":"id","value":"com.diyigemt.arona:*"}},{"effect":"ALLOW","groupType":"ALL","children":[{"effect":"ALLOW","groupType":"ALL","rule":{"type":"Subject","operator":"Equal","key":"type","value":"member"}},{"effect":"ALLOW","groupType":"ALL","rule":{"type":"Environment","operator":"GreaterThan","key":"time","value":"16:00"}}]}]}]}
export type PolicyNodeEffect = "ALLOW" | "DENY";
export type PolicyNodeGroupType = "ALL" | "ANY";
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
  | "IsCHILD";
export interface Policy {
  id: string;
  name: string;
  effect: PolicyNodeEffect;
  rules: PolicyNode[];
}
export interface PolicyNode {
  groupType: PolicyNodeGroupType;
  rule?: PolicyRule;
  children?: PolicyNode[];
}
export interface PolicyRule {
  type: PolicyRuleType;
  operator: PolicyRuleOperator;
  key: string;
  value: string;
}
