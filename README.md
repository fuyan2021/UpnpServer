# Eversolo DLNA Music Server

Eversolo DLNA Music Server 是一个基于 Android 平台的全功能 DLNA/UPnP 媒体服务器，专注于提供高质量的音乐流媒体服务。该项目基于 [DroidDLNA](https://github.com/zhaoxiaodan/DroidDLNA) 进行扩展开发，使用 Cling 库实现 UPnP/DLNA 协议支持。

## 功能特性

### 媒体服务器功能
- ✅ 完整的 DLNA/UPnP 媒体服务器实现
- ✅ 支持音乐库的分类管理（艺术家、专辑艺术家、专辑、单曲、作曲家、流派）
- ✅ 基于 API 的音乐数据获取和同步
- ✅ 自动分页加载大量音乐数据
- ✅ 支持音乐文件的过滤（如 DSF/DFF 格式控制）
- ✅ 音乐元数据管理和展示
- ✅ 图片资源（专辑封面、艺术家头像）的支持

### 技术特性
- ✅ 基于 Cling 库实现 UPnP/DLNA 协议
- ✅ 采用单例模式管理容器加载器
- ✅ 模块化的容器管理架构
- ✅ 异步 API 调用和数据处理
- ✅ 内容树结构管理媒体资源
- ✅ 支持 Android 平台的系统设置集成

## 项目架构

### 核心组件

#### 1. EversoloLibraryService
- **位置**：`upnpserver/src/main/java/com/eversolo/upnpserver/dlna/dms/component/EversoloLibraryService.java`
- **功能**：主要的 DLNA 服务类，负责媒体服务器的初始化、数据加载和容器管理
- **核心方法**：
  - `updateMediaServer()`：更新媒体服务器数据
  - `createOrUpdate*Container()`：创建或更新各种媒体容器
  - `loadApi*List()`：加载 API 数据列表

#### 2. ContainerLoader
- **位置**：`upnpserver/src/main/java/com/eversolo/upnpserver/dlna/dms/loader/ContainerLoader.java`
- **功能**：容器加载工具类，负责容器的创建、更新和音乐数据的加载
- **特性**：
  - 实现了单例模式，确保全局唯一实例
  - 支持自动分页加载大量音乐数据
  - 提供音乐过滤和处理功能

#### 3. ContainerHelper
- **位置**：`upnpserver/src/main/java/com/eversolo/upnpserver/dlna/dms/loader/ContainerHelper.java`
- **功能**：容器管理辅助类，封装容器创建和管理的通用逻辑
- **核心方法**：
  - `createOrUpdate*Container()`：创建或更新各种媒体容器
  - `set*ImageUrl()`：设置容器的图片 URL
  - `create*SubContainers()`：创建子容器结构

#### 4. ContentTree
- **位置**：`upnpserver/src/main/java/com/eversolo/upnpserver/dlna/dms/ContentTree.java`
- **功能**：内容树结构，用于管理媒体资源的层次关系
- **特性**：
  - 支持节点的添加、更新和删除
  - 维护容器和媒体项的引用关系
  - 提供快速查找和访问功能

#### 5. ApiClient
- **位置**：`upnpserver/src/main/java/com/eversolo/upnpserver/dlna/util/ApiClient.java`
- **功能**：API 调用工具类，负责与外部 API 进行通信
- **核心方法**：
  - `getArtistAlbums()`：获取艺术家专辑列表
  - `getArtistMusics()`：获取艺术家单曲列表
  - `getAlbumMusics()`：获取专辑歌曲列表

### 数据模型

#### 1. 音乐信息模型
- `ArtistInfo`：艺术家信息
- `AlbumInfo`：专辑信息
- `AudioInfo`：音频文件信息
- `ComposerInfo`：作曲家信息
- `GenreInfo`：流派信息

#### 2. 容器结构
- 根容器 → 音频容器 → 分类容器（艺术家、专辑艺术家、专辑、单曲、作曲家、流派）
- 分类容器 → 子容器（如艺术家容器 → 艺术家子容器 → 专辑子容器/单曲子容器）

## 使用方法

### 初始化媒体服务器

```java
// 初始化 DLNA 服务
EversoloLibraryService libraryService = new EversoloLibraryService();

// 更新媒体服务器数据
libraryService.updateMediaServer();
```

### 使用 ContainerLoader 加载容器

```java
// 初始化 ContainerLoader 单例
ContainerLoader containerLoader = ContainerLoader.getInstance(context);

// 创建或更新专辑艺术家容器
containerLoader.createOrUpdateAlbumArtistContainer(rootNode, albumArtistInfoList);

// 为专辑加载音乐
containerLoader.loadAlbumMusicsForArtist(container, containerId, albumInfo, artistId, 0);
```

## 开发说明

### 构建环境
- Android Studio
- Gradle 6.8.3
- Android SDK 29+
- Java 8+

### 依赖库
- **Cling**：UPnP/DLNA 协议实现
- **Android SDK**：Android 平台支持
- **Apache HttpClient**：HTTP 通信支持
- **Jetty**：HTTP 服务器支持
- **SLF4J**：日志支持

### 核心配置

#### 系统设置
- `cling_open_dsf_dff`：控制是否启用 DSF/DFF 格式支持
- `cling_settings`：控制 UPnP 服务的开关

#### API 配置
- 基础 API 地址：`http://127.0.0.1:9529/ZidooMusicControl/v2/`
- 图片 API 地址：`http://127.0.0.1:9529/ZidooMusicControl/v2/getImage`

## 代码组织

```
upnpserver/src/main/java/com/eversolo/upnpserver/dlna/
├── application/          # 应用程序基础类
│   ├── BaseApplication.java
│   └── ConfigData.java
├── dms/                  # DLNA 媒体服务器核心
│   ├── bean/            # 数据模型
│   ├── component/       # 组件类
│   ├── loader/          # 加载器类
│   ├── ContentDirectoryService.java
│   ├── ContentNode.java
│   ├── ContentTree.java
│   └── Constant.java
└── util/                # 工具类
    ├── ApiClient.java
    ├── FileHelper.java
    └── UpnpUtil.java
```

## 容器管理流程

1. **容器创建**：通过 `ContainerHelper` 创建基础容器结构
2. **数据加载**：通过 `ApiClient` 从外部 API 获取音乐数据
3. **容器更新**：通过 `ContainerLoader` 更新容器内容
4. **内容树管理**：将容器添加到 `ContentTree` 中进行管理
5. **媒体访问**：通过 UPnP/DLNA 协议提供媒体资源访问

## 注意事项

1. **内存管理**：使用 ApplicationContext 避免内存泄漏
2. **线程安全**：关键操作使用同步机制确保线程安全
3. **性能优化**：采用分页加载处理大量音乐数据
4. **错误处理**：完善的错误日志和异常处理机制
5. **系统兼容性**：适配不同 Android 版本的系统设置

## 许可证

本项目基于 Apache License 2.0 许可证开源，详情请查看 LICENSE 文件。

## 贡献

欢迎提交 Issue 和 Pull Request 来帮助改进项目！

## 联系方式

如有问题或建议，请通过以下方式联系：
- 项目地址：[Eversolo DLNA Music Server](https://github.com/fuyan2021/UpnpServer.git)
- 电子邮件：hammer810@163.com

---

**Eversolo DLNA Music Server** - 提供高质量的 Android DLNA 音乐流媒体服务
