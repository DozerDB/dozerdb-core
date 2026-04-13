<p align="center">
  <img src="./DozerDB_logo.png" width="400" alt="DozerDB">
</p>

<p align="center">
  <em>Enterprise-grade graph database features built on Neo4j Community Edition</em>
</p>

<p align="center">
  <a href="https://github.com/dozerdb/dozerdb-core/blob/main/LICENSE"><img src="https://img.shields.io/badge/License-GPLv3-blue?style=for-the-badge" alt="License"></a>
  <a href="https://hub.docker.com/r/graphstack/dozerdb"><img src="https://img.shields.io/docker/pulls/graphstack/dozerdb?style=for-the-badge&logo=docker&logoColor=white" alt="Docker Pulls"></a>
  <a href="#-development"><img src="https://img.shields.io/badge/Java-17+-orange?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java 17+"></a>
  <a href="https://neo4j.com"><img src="https://img.shields.io/badge/Neo4j-5.25.1-green?style=for-the-badge&logo=neo4j&logoColor=white" alt="Neo4j"></a>
</p>

<p align="center">
  <a href="https://dozerdb.org"><img src="https://img.shields.io/badge/Get_Started-FF6600?style=for-the-badge&logo=readthedocs&logoColor=white" alt="Get Started"></a>
  <a href="https://hub.docker.com/r/graphstack/dozerdb"><img src="https://img.shields.io/badge/Docker_Hub-2496ED?style=for-the-badge&logo=docker&logoColor=white" alt="Docker Hub"></a>
  <a href="mailto:info@greystonesgroup.com"><img src="https://img.shields.io/badge/Contact_Us-333333?style=for-the-badge&logo=gmail&logoColor=white" alt="Contact Us"></a>
</p>

---

## 📋 About

DozerDB enhances Neo4j Community Edition with enterprise features. This repository contains the core engine features responsible for bootstrapping into Neo4j Community Edition.

For the full plugin build (which combines the enhanced browser and core into a single deployable artifact), see [dozerdb-plugin](https://github.com/dozerdb/dozerdb-plugin).

---

## ⚡ Features

- **Enterprise capabilities** on top of Neo4j Community Edition
- **Drop-in enhancement** — bootstraps directly into Neo4j CE
- **Open source** — GPLv3 licensed

---

## 💾 Installation

Visit **[dozerdb.org](https://dozerdb.org)** for full installation instructions.

**Quick options:**

| Method | Link |
|--------|------|
| 🐳 Docker | [`graphstack/dozerdb`](https://hub.docker.com/r/graphstack/dozerdb) |
| 🔌 Plugin JAR | [dozerdb-plugin](https://github.com/dozerdb/dozerdb-plugin) |

---

## 🛠️ Development

### Prerequisites

- **Java 17+** is required. You will get compile errors with older versions.
- We recommend [SDKMAN!](https://sdkman.io/) as an open source Java version manager.

### Building

```bash
./mvnw clean verify
```

### Code Formatting

[Spotless](https://github.com/diffplug/spotless) is used to ensure uniform formatting.

To build locally and skip Spotless checks during development:

```bash
./mvnw clean verify -Dspotless.check.skip -Dspotless.apply.skip
```

If you encounter Spotless errors, run the following to auto-format your code:

```bash
./mvnw spotless:apply
```

> **Note:** Please ensure your code is formatted properly before submitting a pull request.

---

## 🤝 Contributing

Contributions are welcome! Before submitting a pull request:

1. Ensure your code compiles with JDK 17+
2. Run `./mvnw spotless:apply` to format your code
3. Run `./mvnw clean verify` to confirm all checks pass

---

## 📞 Support

<table>
<tr>
<td>

Need help with deployment, graph modeling, or integrating DozerDB into your stack?

Professional support is available for teams and organizations.

<p>
  <a href="mailto:info@greystonesgroup.com"><img src="https://img.shields.io/badge/Email_Us-info@greystonesgroup.com-FF6600?style=for-the-badge&logo=gmail&logoColor=white" alt="Email Us"></a>
  <a href="https://dozerdb.org"><img src="https://img.shields.io/badge/Learn_More-dozerdb.org-333333?style=for-the-badge&logo=googlechrome&logoColor=white" alt="Learn More"></a>
</p>

</td>
</tr>
</table>

---

<p align="center">
  <sub>&copy; 2026 DozerDB Contributors</sub>
</p>
