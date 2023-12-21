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
};
