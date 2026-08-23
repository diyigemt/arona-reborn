export type ChatStickerStatus = "pending" | "ready" | "hidden" | "rejected";

// 与 chatbot 插件 StickerView 一一对应; url = 后端 stickerPublicBaseUrl + 文件名 (nginx 静态映射), 前缀没配时 null.
export interface ChatSticker {
  id: string;
  groupIds: string[];
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
  // 抓取时被拒的没存文件, 不能设为可用
  hasFile: boolean;
}

export interface ChatStickerGroup {
  id: string;
  name: string;
}

// groups 是全库所有来源群 (不随 gid 过滤变), 给下拉用
export interface ChatStickerListResp {
  stickers: ChatSticker[];
  groups: ChatStickerGroup[];
}

export interface ChatStickerEditReq {
  id: string;
  status?: ChatStickerStatus;
  tags?: string[];
  summary?: string;
}
