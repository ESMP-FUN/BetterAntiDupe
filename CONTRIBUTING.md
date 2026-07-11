# Contributing to ESMP Minecraft Plugins

Thank you for your interest in improving our Minecraft plugins! We welcome community contributions, bug fixes, and feature enhancements via Pull Requests. 

Please note that this project is released under a **Source-Available License** (see our `LICENSE` file). By contributing to this repository, you agree to the terms outlined below.

## Developer License Agreement
By submitting a Pull Request, you explicitly agree that:
1. Your contributions are provided under the same non-commercial, non-redistribution terms as the rest of the project.
2. You grant **ESMP** a perpetual, irrevocable, worldwide, royalty-free license to use, modify, and distribute your code within this project.

---

## Technical Guidelines
* **Language**: **Kotlin** is our project standard. Avoid using Java unless explicitly required for specific library compatibility.
* **Code Style**: Follow standard Kotlin coding conventions (official JetBrains style guide). Ensure your code is clean, idiomatic, and properly documented.
* **Build System**: Ensure any dependencies you add are properly scoped in the Gradle build files (`build.gradle.kts`).
* **Performance**: Minecraft server performance is critical. Avoid heavy, unoptimized operations on the main server thread. Use asynchronous tasks where appropriate.

---

## How to Contribute

### 1. Report Bugs or Request Features
Before writing code, please check the [Issues](../../issues) tab to see if your topic is already being discussed. If not, open a new issue using our issue template.

### 2. Submitting a Pull Request (PR)
1. **Fork the Repository**: Create your own copy of this repository on GitHub.
2. **Create a Branch**: Create an isolated branch for your changes (e.g., `git checkout -b feature/cool-mechanic` or `git checkout -b fix/event-leak`).
3. **Make Your Changes**: Write your Kotlin code, keeping server resource usage in mind.
4. **Test Locally**: Build the `.jar` and test it on a local development Minecraft server. Ensure it runs smoothly without throwing stack traces in the console.
5. **Commit & Push**: Commit your changes with descriptive messages and push them to your fork.
6. **Open a PR**: Submit a Pull Request from your fork back to our `main` branch.

Thank you for helping us optimize and expand our Minecraft plugins!
