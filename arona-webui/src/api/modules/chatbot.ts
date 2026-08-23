import service, { simplifiedApiService } from "@/api/http";
import type { ChatSticker, ChatStickerEditReq } from "@/interface";

export const ChatbotApi = {
  fetchStickers(gid: string) {
    return simplifiedApiService(
      service.raw<ChatSticker[]>({
        url: "/chatbot/sticker/list",
        method: "GET",
        params: { gid },
      }),
    );
  },
  updateSticker(data: ChatStickerEditReq) {
    return simplifiedApiService(
      service.raw<void>({
        url: "/chatbot/sticker/update",
        method: "POST",
        data,
      }),
    );
  },
  deleteSticker(gid: string, id: string) {
    return simplifiedApiService(
      service.raw<void>({
        url: "/chatbot/sticker",
        method: "DELETE",
        data: { gid, id },
      }),
    );
  },
};
