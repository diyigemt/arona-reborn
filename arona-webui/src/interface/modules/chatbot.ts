export type ChatStickerStatus = "pending" | "ready" | "hidden" | "rejected";

// 与 chatbot 插件 StickerView 一一对应; url = 后端 stickerPublicBaseUrl + 文件名 (nginx 静态映射), 前缀没配时 null.
export interface ChatSticker {
  id: string;
  status: ChatStickerStatus;
  tags: string[];
  summary: string;
  nsfwRisk: string | null;
  width: number | null;
  height: number | null;
  bytes: number | null;
  useCount: number;
  createdAt: number | null;
  lastUsedAt: number | null;
  senderId: string | null;
  url: string | null;
}

export interface ChatStickerEditReq {
  gid: string;
  id: string;
  status?: ChatStickerStatus;
  tags?: string[];
  summary?: string;
}
