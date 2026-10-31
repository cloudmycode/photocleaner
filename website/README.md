# MaxDevLab website

英文应用展示站：Photo Cleaner、Security Antivirus Max Cleaner、隐私中心、使用条款、支持与 FAQ。品牌与联系邮箱使用用户确认的 MaxDevLab / cleanmaxdev@gmail.com。

## 本地预览

在工程根目录运行：

```sh
python3 website/build.py
python3 -m http.server 8787 --bind 127.0.0.1 --directory website/dist
```

打开 http://127.0.0.1:8787/ 。生成器只依赖 Python 标准库，部署后仅有静态 HTML、CSS、JS 和本地图片，不需要数据库或 Node 服务。

## 维护

- `site.json`：品牌、邮箱、正式域名及两款应用的商店链接。
- `pages/`：页面内容；`templates/base.html`：共用导航与页脚。
- `assets/`：样式、交互与图片；`dist/`：生成的发布文件，勿直接修改。
- `preview/`：电脑与手机截图、浏览器验证结果。
- `tools/preview.cjs`：本机 Playwright 浏览器检查；使用本机已有 Chrome 和 Playwright 路径。仅开发检查使用，不是网站运行依赖。

两款应用均在首页、详情页和对应隐私政策页提供 Google Play 链接。商店地址集中在 `site.json`，不再使用 Coming soon 状态隐藏入口。实际可下载地区和设备以 Google Play 为准。

支持表单只打开用户邮箱的邮件草稿，不会在网站中发送、存储表单内容。网站无分析脚本、外部字体或跟踪 Cookie；最终主机仍可能记录访问日志。

## 部署与 Google Play 链接

1. 在 `site.json` 填入实际 HTTPS 域名（不带路径），重新构建。也可用 `python3 website/build.py --domain https://你的实际域名` 临时指定。
2. 将 **`dist/` 的内容**放到静态站点根目录。不要上传 Android 工程、签名、配置凭据或整个仓库。
3. 配置 HTTPS、404 页面与响应头。`_headers` 适用于支持这种格式的主机；其他主机需配置等价响应头。
4. 公开访问并检查以下地址，再填写 Google Play / AdMob：
   - Photo Cleaner 隐私政策：`/privacy/photo-cleaner.html`
   - Security Antivirus 隐私政策：`/privacy/security-antivirus.html`
   - 支持：`/support/index.html`
   - 广告卖家声明：`/app-ads.txt`
5. Android 应用中的隐私链接需在正式地址上线后更新。网站已于 2026-10-31 部署至 https://www.maxdevlab.com；本次未修改 Android 链接。部署详情见 `deploy/DEPLOYMENT.md`。

正式域名为 https://www.maxdevlab.com，已生成对应 canonical 和 sitemap。Google Play 应使用正式 HTTPS 隐私政策地址，而非本地预览地址。

`app-ads.txt` 只包含本次已确认的 AdMob 发布商 `pub-7579918370231503`。如安全应用使用其他广告账号，需按该账号的真实配置补充。

不要直接覆盖现有 `antivirus.maxdevlab.com` 服务目录：现有工程还包含应用后端；本网站是独立静态站点。

## 隐私内容与验证范围

Photo Cleaner 政策依据当前 Android 工程的照片权限、本地处理、删除、提醒及 AdMob / UMP 行为整理；明确区分照片不上传与广告服务处理设备数据。

安全清理应用的隐私政策和 EULA 沿用现有工程的已发布文档；隐私页面增加摘要与“设备标识可能属于个人数据”的说明，未重新审计该应用的全部 SDK。正式迁移或更新政策前，应将其中的设备标识、第三方服务和保留说明与实际发行版及 Google Play Data safety 表核对一致；制作网站不等于完成政策审核。

已检查 11 个页面的电脑与手机显示、图片加载、页面标题、无横向溢出、手机菜单、FAQ 与支持表单必填项。截图与结果在 `preview/`。
