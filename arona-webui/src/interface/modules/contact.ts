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
  policies: Policy[];
  roles: ContactRole[];
  members: ContactMember[];
  registerTime: string;
}

export interface ContactUpdateReq {
  id: string;
  contactName: string;
}
