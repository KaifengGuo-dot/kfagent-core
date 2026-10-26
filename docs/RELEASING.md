# 发布与仓库设置

这份文档是发布清单，也记录了一些踩过的坑。第一次发版前建议从头看一遍。

## 1. 版本号

遵循 Semantic Versioning：

- `MAJOR`：不兼容的 API 变化；
- `MINOR`：向后兼容的新功能；
- `PATCH`：向后兼容的修复。

当前版本在 `pom.xml` 的 `<version>`，tag 用 `v` 前缀，例如 `v1.0.0`。

## 2. 发布前检查

- [ ] `CHANGELOG.md` 把 `Unreleased` 改成新版本号，写日期；
- [ ] `pom.xml` 版本号更新；
- [ ] README 里的依赖版本更新；
- [ ] `mvn -B -DskipTests package` 通过；
- [ ] `mvn -B -DskipTests -Prelease-artifacts package` 通过（生成 sources / javadoc）；
- [ ] 两个离线自测通过；
- [ ] `docs/ARCHITECTURE.md` / `docs/FAQ.md` 里没有过期描述；
- [ ] 没有把 API Key、内网地址、个人数据提交进去。

> Windows 中文用户名下，javadoc 插件可能报 `unmappable character`。先设置
> `JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8`，或者直接在 CI / ASCII 路径下构建。

## 3. GitHub Release

打 tag 就会触发 `.github/workflows/release.yml`：

```bash
git tag -a v1.0.0 -m "kfagent-core 1.0.0"
git push origin v1.0.0
```

工作流会做四件事：

1. 校验 tag 和 `pom.xml` 版本一致，不一致直接失败；
2. 构建；
3. 跑两个离线自测；
4. 用 `gh release create` 创建 Release，上传 `target/kfagent-core-*.jar`
   （主 jar、sources、javadoc 三个文件），并自动生成 release notes。

不要手动在网页上创建 Release，容易漏文件。

## 4. JitPack（当前发布方式）

`groupId` 是 `io.github.kfagent`，但 Sonatype 对 `io.github.*` 的验证规则是：GitHub 账号或
组织名必须等于命名空间里的那一段。也就是说，`io.github.kfagent` 需要你拥有一个叫 `kfagent`
的 GitHub 账号或组织。

GitHub 上 `kfagent` 这个用户名已经被别人占用，组织也不能和用户重名，所以这条命名空间验证
走不通。当前发布走 JitPack，按 tag 构建，不需要 GPG 和命名空间验证。

### 4.1 JitPack 配置

仓库根目录的 `jitpack.yml` 指定 JDK 和构建命令：

```yaml
jdk:
  - openjdk17

install:
  - bash ./mvnw -B -DskipTests install
```

用 Maven Wrapper 而不是 `mvn`：JitPack 自带的 Maven 版本低于 enforcer 要求的 3.6.3，
直接跑 `mvn` 会在构建开头失败。

用户侧坐标：

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}
dependencies {
    implementation 'com.github.KaifengGuo-dot:kfagent-core:v1.0.1'
}
```

### 4.2 JitPack 发版

1. 按第 2 节改版本号、CHANGELOG，提交；
2. 打 tag 推到远端，等 `release.yml` 跑完；
3. 浏览器打开 `https://jitpack.io/#KaifengGuo-dot/kfagent-core`，看到新 tag 后点 Get it，
   首次构建要一两分钟；
4. 返回 200 后，在一个干净目录里用空 `settings.xml` 验证：

   ```bash
   mvn -B -DskipTests -s settings.xml dependency:resolve
   ```

   之所以要空 `settings.xml`，是因为本地 `~/.m2/settings.xml` 里的
   `<mirrorOf>*</mirrorOf>` 会把 JitPack 也一起镜像到阿里云，导致看起来“拉不到”。

注意 JitPack 按 commit 缓存构建结果。**tag 已经构建失败或移动过，改 tag 不会重新构建**，
只能换一个新版本号（这也是 v1.0.0 之后直接发 v1.0.1 的原因）。强制重建需要 JitPack token。

## 5. 以后要上 Maven Central

保持包名 `io.github.kfagent` 不变的前提下，唯一可行的做法是把 `groupId` 改成
`io.github.kaifengguo-dot`，然后在 GitHub 建一个同名公开仓库用于验证。代价是坐标和包名
不一致，需要同时改 README、CHANGELOG 和 pom 里的 `central` profile。

Central 的发布流程本身不变，下面几节保留备用。

### 5.1 验证命名空间

1. 登录 <https://central.sonatype.com/>；
2. Namespaces -> Add Namespace；
3. 填 `io.github.kaifengguo-dot`；
4. 按提示在 GitHub 建验证仓库；
5. 回到页面点 Verify。

验证通过后，这个 namespace 下的所有 artifact 都能发布。

### 5.2 准备 GPG

```bash
gpg --full-generate-key
# 选 RSA，4096 位，设置过期时间和 passphrase

gpg --list-secret-keys --keyid-format=long
# 记下 sec 那一行的 key id，例如 ABCD1234EF567890

gpg --armor --export-secret-keys ABCD1234EF567890
# 整段输出（包括 BEGIN/END 行）就是 GPG_PRIVATE_KEY
```

公钥要推到 keyserver，否则 Central 校验签名时找不到：

```bash
gpg --keyserver keyserver.ubuntu.com --send-keys ABCD1234EF567890
```

### 5.3 配 GitHub Secrets

在仓库 Settings -> Secrets and variables -> Actions 里加四个：

| Secret | 内容 |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Central Portal 用户 token 的 username |
| `MAVEN_CENTRAL_TOKEN` | Central Portal 用户 token 的 password |
| `GPG_PRIVATE_KEY` | ASCII-armored 私钥全文 |
| `GPG_PASSPHRASE` | GPG 私钥的 passphrase |

用户 token 在 <https://central.sonatype.com/account> 生成，不是登录密码。

### 5.4 发布

1. Actions -> **Publish to Maven Central** -> Run workflow；
2. 工作流会构建、跑自测、签名、上传到 Central Portal；
3. 因为 `autoPublish=false`，不会自动上线。去 Central Portal ->
   Deployments 找到这次上传，检查 POM、jar、sources、javadoc、签名，然后点 Publish；
4. 等 10-30 分钟，检查 `https://repo1.maven.org/maven2/io/github/<你的 groupId 末段>/kfagent-core/`；
5. 找一个干净目录，用新版本依赖跑一次 `mvn dependency:get` 验证。

本地想先试跑，可以在 `~/.m2/settings.xml` 配：

```xml
<settings>
    <servers>
        <server>
            <id>central</id>
            <username>token-username</username>
            <password>token-password</password>
        </server>
    </servers>
</settings>
```

然后：

```bash
mvn -Pcentral deploy
```

## 6. GitHub 仓库设置

这些不在代码里，但决定了别人第一眼看到什么。

### 6.1 About

- Description：`零依赖的 Java Agent 循环库：工具调用、SSE 流式、会话存储，JDK 17。`
- Website：填 README 或文档地址；
- Topics：

  ```
  java java17 ai-agent llm agent openai deepseek function-calling tool-calling
  zero-dependency maven-library
  ```

### 6.2 功能开关

- Settings -> General -> Features：打开 **Discussions**（Issue 模板里的联系链接指向它）；
- Settings -> Code security and analysis：打开 **Private vulnerability reporting**
  和 **Dependabot alerts**；
- Settings -> Actions -> General：允许 GitHub Actions 运行，默认权限设为只读。

### 6.3 分支保护

`main` 分支建议加一条 ruleset：

- 禁止 force push；
- 禁止删除；
- 合并前必须通过 `CI` 的 JDK 17 / 21 两个 job；
- 至少 1 个 approve（如果以后有别的维护者）。

个人项目可以不开 approve，但状态检查建议开。

### 6.4 社交预览

Settings -> General -> Social preview 上传一张 1280x640 的图。
没有设计稿时，用仓库名 + 一句话 + 深色背景就够，别放太多字。

## 7. 发布后

- 更新 README 的安装版本和 Roadmap；
- 在 CSDN / 掘金 / V2EX 发一篇更新说明，别只贴链接，讲清楚这次解决了什么；
- 盯一周 issue，尤其是新模型网关的兼容问题；
- 把这次踩的坑补进 `docs/FAQ.md`。
