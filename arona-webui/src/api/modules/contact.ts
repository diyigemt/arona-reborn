import service from "@/api/http";

// eslint-disable-next-line import/prefer-default-export
export const ContactApi = {
  fetchContacts() {
    return service.raw<string>({
      url: "/contact/contacts",
      method: "GET",
    });
  },
};
