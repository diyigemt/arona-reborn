export interface Student {
  id: number;
  name: string;
  limit: StudentLimitType;
  rarity: StudentRarity;
}
export type StudentRarity = "R" | "SR" | "SSR";
export type StudentLimitType = "Unique" | "Event" | "Permanent";
