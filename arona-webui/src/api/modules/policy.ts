import service, { simplifiedApiService } from "@/api/http";
import { EditableContact, Policy, PolicyResource } from "@/interface";

// eslint-disable-next-line import/prefer-default-export
export const PolicyApi = {
  fetchUserDefinePolicy() {
    return service.raw<Policy[]>({
      url: "",
    });
  },
  fetchResources() {
    return simplifiedApiService(
      service.raw<PolicyResource[]>({
        url: "/policy/resources",
        method: "GET",
      }),
    );
  },
};
