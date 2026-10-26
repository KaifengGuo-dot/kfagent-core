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

## 4. 发 Maven Central

### 4.1 先解决命名空间

`groupId` 是 `io.github.kfagent`，但 GitHub 账号是 `KaifengGuo-dot`。

Sonatype 对 `io.github.*` 的验证规则是：GitHub 账号或组织名必须等于命名空间里的
那一段。也就是说，`io.github.kfagent` 需要你拥有一个叫 `kfagent` 的 GitHub 账号或组织，
并在它下面建一个名为 `io.github.kfagent` 的公开仓库（内容随意，放个 README 就行），
然后到 Central Portal 点 Verify。

三条路，按推荐顺序：

1. **建 `kfagent` 组织，把仓库迁过去。** 包名、groupId、README 都不用改，最干净。
   组织免费，仓库迁移 GitHub 会自动重定向。
2. **改 groupId 为 `io.github.kaifengguo-dot`。** 能通过验证，
   但 Java 包名仍然是 `io.github.kfagent`，坐标和包名不一致，不推荐。
3. **不上 Central，用 JitPack。** 最省事，用户加一个仓库即可：

   ```groovy
   repositories {
       maven { url 'https://jitpack.io' }
   }
   dependencies {
       implementation 'com.github.KaifengGuo-dot:kfagent-core:v1.0.0'
   }
   ```

   JitPack 按 tag 构建，不需要 GPG、不需要命名空间验证。缺点是用户要多配一个仓库，
   而且首次构建较慢。

GitHub Packages 也能发，但用户拉取需要 token，对“加一行依赖”的库不友好，不作为首选。

### 4.2 验证命名空间

1. 登录 <https://central.sonatype.com/>；
2. Namespaces -> Add Namespace；
3. 填 `io.github.kfagent`；
4. 按提示在 GitHub 建验证仓库；
5. 回到页面点 Verify。

验证通过后，这个 namespace 下的所有 artifact 都能发布。

### 4.3 准备 GPG

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

### 4.4 配 GitHub Secrets

在仓库 Settings -> Secrets and variables -> Actions 里加四个：

| Secret | 内容 |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Central Portal 用户 token 的 username |
| `MAVEN_CENTRAL_TOKEN` | Central Portal 用户 token 的 password |
| `GPG_PRIVATE_KEY` | ASCII-armored 私钥全文 |
| `GPG_PASSPHRASE` | GPG 私钥的 passphrase |

用户 token 在 <https://central.sonatype.com/account> 生成，不是登录密码。

### 4.5 发布

1. Actions -> **Publish to Maven Central** -> Run workflow；
2. 工作流会构建、跑自测、签名、上传到 Central Portal；
3. 因为 `autoPublish=false`，不会自动上线。去 Central Portal ->
   Deployments 找到这次上传，检查 POM、jar、sources、javadoc、签名，然后点 Publish；
4. 等 10-30 分钟，检查 <https://repo1.maven.org/maven2/io/github/kfagent/kfagent-core/>；
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

## 5. GitHub 仓库设置

这些不在代码里，但决定了别人第一眼看到什么。

### 5.1 About

- Description：`零依赖的 Java Agent 循环库：工具调用、SSE 流式、会话存储，JDK 17。`
- Website：填 README 或文档地址；
- Topics：

  ```
  java java17 ai-agent llm agent openai deepseek function-calling tool-calling
  zero-dependency maven-library
  ```

### 5.2 功能开关

- Settings -> General -> Features：打开 **Discussions**（Issue 模板里的联系链接指向它）；
- Settings -> Code security and analysis：打开 **Private vulnerability reporting**
  和 **Dependabot alerts**；
- Settings -> Actions -> General：允许 GitHub Actions 运行，默认权限设为只读。

### 5.3 分支保护

`main` 分支建议加一条 ruleset：

- 禁止 force push；
- 禁止删除；
- 合并前必须通过 `CI` 的 JDK 17 / 21 两个 job；
- 至少 1 个 approve（如果以后有别的维护者）。

个人项目可以不开 approve，但状态检查建议开。

### 5.4 社交预览

Settings -> General -> Social preview 上传一张 1280x640 的图。
没有设计稿时，用仓库名 + 一句话 + 深色背景就够，别放太多字。

## 6. 发布后

- 更新 README 的安装版本和 Roadmap；
- 在 CSDN / 掘金 / V2EX 发一篇更新说明，别只贴链接，讲清楚这次解决了什么；
- 盯一周 issue，尤其是新模型网关的兼容问题；
- 把这次踩的坑补进 `docs/FAQ.md`。
