import { User } from "@/interface";

export interface BaseStoreState {
  token: string;
  user: User;
  clarity: boolean;
}

export interface SettingStoreState {
  theme: {
    themeType: string;
    themeColor: string | number;
  };
}
