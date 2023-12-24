import { User } from "@/interface";

export interface BaseStoreState {
  token: string;
  user: User;
}

export interface SettingStoreState {
  theme: {
    themeType: string;
    themeColor: string | number;
  };
}
