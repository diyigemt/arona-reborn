import service, { simplifiedApiService } from "@/api/http";
import { AronaImage } from "@/interface";

// eslint-disable-next-line import/prefer-default-export
export const AronaApi = {
  trainerImage(name: string) {
    return service.raw<AronaImage[]>({
      baseURL: "https://arona.diyigemt.com/api/v2",
      url: "/image",
      params: {
        name,
      },
      showServerResponseError: false,
    });
  },
};
