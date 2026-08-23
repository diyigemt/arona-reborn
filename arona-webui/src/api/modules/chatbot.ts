import service, { simplifiedApiService } from "@/api/http";
import type { ChatStickerEditReq, ChatStickerListResp } from "@/interface";

// 仅后端主配置 superAdminUid 里的用户可用, 其它人拿到 601 权限不足
export const ChatbotApi = {
  fetchStickers(gid?: string) {
    return simplifiedApiService(
      service.raw<ChatStickerListResp>({
        url: "/chatbot/sticker/list",
        method: "GET",
        params: gid ? { gid } : undefined,
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
  deleteSticker(id: string) {
    return simplifiedApiService(
      service.raw<void>({
        url: "/chatbot/sticker",
        method: "DELETE",
        data: { id },
      }),
    );
  },
};
