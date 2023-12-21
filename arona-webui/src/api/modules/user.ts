import service, { simplifiedApiService } from "@/api/http";
import { User } from "@/interface";

interface AuthResp {
  status: 0 | 1 | 2; // 0 1 2 无效 等待 成功
  token: string;
}
// eslint-disable-next-line import/prefer-default-export
export const UserApi = {
  login() {
    return simplifiedApiService(
      service.raw<string>({
        url: "/user/login",
        method: "GET",
      }),
    );
  },
  fetchLoginState(token: string) {
    return simplifiedApiService(
      service.raw<AuthResp>({
        url: "/user/login",
        method: "GET",
        params: {
          token,
        },
      }),
    );
  },
  fetchUserProfile() {
    return simplifiedApiService(
      service.raw<User>({
        url: "/user",
        method: "GET",
      }),
    );
  },
  updateUserProfile(data: User) {
    return simplifiedApiService(
      service.raw<void>({
        url: "/user",
        method: "PUT",
        data,
      }),
    );
  }
};
