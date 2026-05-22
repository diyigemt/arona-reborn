import { Policy } from "@/interface";

export type ContactType = "Private" | "PrivateGuild" | "Group" | "Guild";
export interface ContactRole {
  id: string;
  name: string;
}
export interface ContactMember {
  id: string;
  name: string;
  roles: string[];
}
export interface Contact {
  id: string;
  contactName: string;
  contactType: ContactType;
  // 仅 /contact?id= 完整接口才返回; 列表接口 /contacts /manage-contacts 不下发, 故标 optional.
  policies?: Policy[];
  roles: ContactRole[];
  members: ContactMember[];
  registerTime?: string;
  // pluginNs -> configKey -> 配置 JSON 对象; 后端已是结构化叶子, 不再做 JSON.stringify
  config?: Record<string, Record<string, Record<string, unknown>>>;
}
export interface EditableContactRole extends ContactRole {
  edit: boolean;
}
export interface EditableContactMember extends ContactMember {
  edit: boolean;
}
export interface EditableContact extends Contact {
  roles: EditableContactRole[];
  members: EditableContactMember[];
}
export interface ContactUpdateReq {
  id: string;
  contactName: string;
}
