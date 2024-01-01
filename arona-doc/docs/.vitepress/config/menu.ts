import {DefaultTheme} from 'vitepress';

const V1SideBar: DefaultTheme.Sidebar = [{
  text: '开始',
  items: [{
    text: '简介',
    link: '/guide/what-is',
  }, {
    text: '声明',
    link: '/guide/announcement',
  }, {
    text: '名词',
    link: '/guide/glossary',
  }],
}, {
  text: '安装',
  collapsed: false,
  items: [{
    text: '安装mirai-console',
    link: '/install/mirai-console',
  }, {
    text: '登录bot',
    link: '/install/login-bot',
  }, {
    text: '安装chat-command',
    link: '/install/chat-command',
  }, {
    text: '安装arona',
    link: '/install/arona',
  }, {
    text: '疑难解答',
    link: '/install/qa',
  }],
}, {
  text: '配置',
  collapsed: false,
  items: [{
    text: '基础配置',
    link: '/config/base-config'
  }, {
    text: '数据库',
    link: '/config/database'
  }]
}, {
  text: '功能',
  collapsed: false,
  items: [{
    text: '主动触发',
    link: '/command/manual'
  }, {
    text: '非主动触发',
    link: '/command/auto'
  }, {
    text: '远端服务',
    link: '/command/remote'
  }]
}, {
  text: '简版用户手册',
  link: '/manual/command'
}, {
  text: '其他',
  collapsed: false,
  items: [{
    text: '帮助',
    link: '/other/help'
  }, {
    text: '鸣谢',
    link: '/other/thanks'
  }]
}].map((it: DefaultTheme.SidebarItem) => {
  it.base = "/v1/";
  return it;
});

const MainSideBar: DefaultTheme.Sidebar = [{
  text: "开始",
  items: [{
    text: '简介',
    link: '/guide/what-is',
  }]
}, {
  text: '简版用户手册',
  collapsed: false,
  items: [{
    text: "指令",
    link: "/manual/command"
  }, {
    text: "webui",
    link: "/manual/webui"
  }]
}, {
  text: 'webui',
  collapsed: false,
  items: [{
    text: "登录",
    link: "/webui/login"
  }, {
    text: "群管理",
    link: "/webui/contact"
  }, {
    text: "策略管理",
    link: "/webui/policy"
  }, {
    text: "插件偏好",
    link: "/webui/plugins"
  }, {
    text: "个人中心",
    link: "/webui/user"
  }]
}].map((it: DefaultTheme.SidebarItem) => {
  it.base = "/v2/";
  return it;
});

export const SidebarItem: DefaultTheme.SidebarMulti = {
  '/v1/': V1SideBar,
  '/v2/': MainSideBar
};

export const NavItem: DefaultTheme.NavItem[] = [{
  text: '首页',
  link: '/',
}, {
  text: '简介',
  items: [{
    text: '官方版',
    link: '/v2/guide/what-is',
  }, {
    text: '自行部署版',
    link: '/v1/guide/what-is',
  }]
}, {
  text: '用户手册',
  items: [{
    text: '官方版',
    link: '/v2/manual/command',
  }, {
    text: '自行部署版',
    link: '/v1/manual/',
  }]
}, {
  text: '更新日志',
  items: [{
    text: '官方版1.0.0',
    link: '/v2/other/changelog',
  }, {
    text: '自行部署版1.1.14',
    link: '/v1/other/changelog',
  }],
}, {
  text: 'api公开计划',
  link: '/v1/api/index',
}, {
  text: '最后的公告',
  link: '/v1/api/announcement',
}];
