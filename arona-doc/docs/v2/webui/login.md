# 登录

由于浏览器安全策略的限制，用户必须先在此域名进行至少一次交互后才能够播放音频/视频，因此必须得点一下才能开始播放pv

点击Arona登录后，会提供6位数的随机验证码，需要通过[登录指令](../manual/command#login)来进行登录

验证码有效时间为10分钟，每60s可以请求刷新验证码

登录成功时，Bot会提示认证成功，同时webui认证码窗口关闭，并在右下角显示当前UID，此时再点击一次页面即可进入主菜单

::: details 登录成功

<img src="/image/webui/login/login-success.webp" alt="login-success" />

:::

若登录失败，可以尝试在webui上重新获取认证码

::: details 登录失败

<img src="/image/webui/login/login-token-error.webp" alt="login-token-error" />

:::