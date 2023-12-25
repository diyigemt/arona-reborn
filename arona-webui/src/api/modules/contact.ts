import service, { simplifiedApiService } from "@/api/http";
import { Contact, EditableContact, ContactUpdateReq, ContactMember, ContactRole, Policy } from "@/interface";

// eslint-disable-next-line import/prefer-default-export
export const ContactApi = {
  fetchContacts() {
    return simplifiedApiService(
      service.raw<Contact[]>({
        url: "/contact/contacts",
        method: "GET",
      }),
    );
  },
  fetchManageContacts() {
    return simplifiedApiService(
      service.raw<Contact[]>({
        url: "/contact/manage-contacts",
        method: "GET",
      }),
    );
  },
  fetchContact(id: string) {
    return simplifiedApiService(
      service.raw<EditableContact>({
        url: "/contact/contact",
        method: "GET",
        params: {
          id,
        },
      }),
    ).then((data) => {
      // eslint-disable-next-line no-return-assign
      data.roles.forEach((it) => (it.edit = false));
      // eslint-disable-next-line no-return-assign
      data.members.forEach((it) => (it.edit = false));
      return data;
    });
  },
  fetchContactBase(id: string) {
    return simplifiedApiService(
      service.raw<EditableContact>({
        url: "/contact/contact-base",
        method: "GET",
        params: {
          id,
        },
      }),
    ).then((data) => {
      // eslint-disable-next-line no-return-assign
      data.roles.forEach((it) => (it.edit = false));
      // eslint-disable-next-line no-return-assign
      data.members.forEach((it) => (it.edit = false));
      return data;
    });
  },
  updateContact(data: ContactUpdateReq) {
    return simplifiedApiService(
      service.raw<null>({
        url: "/contact/contact-basic",
        method: "POST",
        data,
      }),
    );
  },
  updateContactMember(id: string, data: ContactMember) {
    return simplifiedApiService(
      service.raw<null>({
        url: `/contact/${id}/member`,
        method: "PUT",
        data,
      }),
    );
  },
  createContactRole(id: string, data: ContactRole) {
    return simplifiedApiService(
      service.raw<null>({
        url: `/contact/${id}/role`,
        method: "POST",
        data,
      }),
    );
  },
  updateContactRole(id: string, data: ContactRole) {
    return simplifiedApiService(
      service.raw<null>({
        url: `/contact/${id}/role`,
        method: "PUT",
        data,
      }),
    );
  },
  deleteContactRole(id: string, data: ContactRole) {
    return simplifiedApiService(
      service.raw<null>({
        url: `/contact/${id}/role`,
        method: "DELETE",
        data,
      }),
    );
  },
  fetchContactPolicies(id: string) {
    return simplifiedApiService(
      service.raw<Policy[]>({
        url: `/contact/${id}/policies`,
        method: "GET",
      }),
    );
  },
  fetchContactPolicy(id: string, policyId: string) {
    return simplifiedApiService(
      service.raw<Policy>({
        url: `/contact/${id}/policy`,
        method: "GET",
        params: {
          pid: policyId,
        },
      }),
    );
  },
  updateContactPolicy(id: string, policy: Policy) {
    return simplifiedApiService(
      service.raw<string>({
        url: `/contact/${id}/policy`,
        method: "PUT",
        data: policy,
      }),
    );
  },
  createContactPolicy(id: string, policy: Policy) {
    return simplifiedApiService(
      service.raw<string>({
        url: `/contact/${id}/policy`,
        method: "POST",
        data: policy,
      }),
    );
  },
  deleteContactPolicy(id: string, pid: string) {
    return simplifiedApiService(
      service.raw<void>({
        url: `/contact/${id}/policy`,
        method: "DELETE",
        data: {
          id: pid,
        },
      }),
    );
  },
  updatePluginPreference(cid: string, pid: string, pKey: string, preference: string | object) {
    return simplifiedApiService(
      service.raw<void>({
        url: `/contact/${cid}/plugin/preference`,
        method: "POST",
        data: {
          id: pid,
          key: pKey,
          value: typeof preference === "string" ? preference : JSON.stringify(preference),
        },
      }),
    );
  },
  fetchMemberPluginPreference(cid: string, pid: string, pKey: string) {
    return simplifiedApiService(
      service.raw<string>({
        url: `/contact/${cid}/member/plugin/member-preference`,
        method: "GET",
        params: {
          pid,
          key: pKey,
        },
      }),
    );
  },
  updateMemberPluginPreference(cid: string, pid: string, pKey: string, preference: string | object) {
    return simplifiedApiService(
      service.raw<void>({
        url: `/contact/${cid}/member/plugin/member-preference`,
        method: "POST",
        data: {
          id: pid,
          key: pKey,
          value: typeof preference === "string" ? preference : JSON.stringify(preference),
        },
      }),
    );
  },
};
