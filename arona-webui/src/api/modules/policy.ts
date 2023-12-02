import service from "@/api/http";
import { Policy } from "@/interface";

// eslint-disable-next-line import/prefer-default-export
export const PolicyApi = {
  fetchUserDefinePolicy() {
    return service.raw<Policy[]>({
      url: "",
    });
  },
};
