export * from "./modules/user";
export * from "./modules/policy";
export * from "./modules/plugin";
export * from "./modules/contact";
export interface SelectOption {
  id?: string;
  label: string;
  value: string | number;
}

export type SelectOptions = SelectOption[];
