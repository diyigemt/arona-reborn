import service, { simplifiedApiService } from "@/api/http";
import { AronaImage } from "@/interface";

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
