# Compatibility matrix

ShamooRuntime pins platform APIs and native/runtime dependencies. A version not listed here is unsupported until its
generated model, process harness, and security tests pass and this matrix is updated.

| Artifact | Java | Host | Generated API | Runtime-specific boundary |
| --- | --- | --- | --- | --- |
| Paper | 21 | Paper 1.21.8 build 55 | Paper API `1.21.8-R0.1-20250906.215025-55`, Adventure `4.24.0` | Mojang-mapped Paper 1.21.8 build 55, Mache 2 |
| Velocity | 21 | Velocity 3.4.0 build 566 process fixture | Velocity API `3.4.0`, Adventure `4.26.1` | No Paper or NMS classes |

Both artifacts embed Javet/Node `5.0.9`; the distributed native runtime currently supports Linux x86-64 only. Paper
and Velocity JARs are not interchangeable. Paper packet interception additionally requires its exact listed server
identity and remains disabled unless explicitly configured and allowed per plugin.

`verifyPaperApiCoverage` and `verifyVelocityApiCoverage` independently rescan their own pinned API and Adventure
artifacts. Each task compares its model and 100% declaration/member/event/exception inventory with the corresponding
checked-in descriptor, so one platform cannot satisfy or mask the other's coverage result.
