import service, { simplifiedApiService } from "@/api/http";
import { Contact, ContactUpdateReq } from "@/interface";

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
      service.raw<Contact>({
        url: "/contact/contact",
        method: "GET",
        params: {
          id,
        },
      }),
    );
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
};
