import {DefaultTheme} from 'vitepress';

const MainSideBar: DefaultTheme.Sidebar = [{
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
    text: '安装本体',
    link: '/install/',
  }],
}, {
  text: '用户手册',
  link: '/manual/'
}, {
  text: '其他',
  collapsed: false,
  items: [{
    text: '帮助',
    link: '/other/help'
  }, {
    text: '更新日志',
    link: '/other/changelog'
  }]
}];

export const SidebarItem: DefaultTheme.Sidebar = {
  '/guide/': MainSideBar,
  '/install/': MainSideBar,
  '/config/': MainSideBar,
  '/command/': MainSideBar,
  '/manual/': MainSideBar,
  '/other/': MainSideBar,
};

export const NavItem: DefaultTheme.NavItem[] = [{
  text: '首页',
  link: '/',
}, {
  text: '简介',
  link: '/guide/what-is',
}, {
  text: '0.0.1',
  items: [{
    text: 'Changelog',
    link: '/other/changelog',
  }],
}, {
  text: 'api公开计划',
  link: '/api/index',
}];
