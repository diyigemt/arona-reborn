import service from "@/api/http";

interface AuthResp {
  status: 0 | 1 | 2; // 0 1 2 无效 等待 成功
  token: string;
}
// eslint-disable-next-line import/prefer-default-export
export const UserApi = {
  login() {
    return service.raw<string>({
      url: "/user/login",
      method: "GET",
    });
  },
  fetchLoginState(token: string) {
    return service.raw<AuthResp>({
      url: "/user/login",
      method: "GET",
      params: {
        token,
      },
    });
  },
};
