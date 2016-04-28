![License](https://img.shields.io/badge/license-MIT-blue.svg?style=flat "MIT")

## Some experiments with akka-stream and akka-http

This repository contains some personal experiments with the akka-http client APIs and akka-stream, mostly to learn how to think in streams and how to solve some non-trivial problems I had previously solved in Spray.

### Experiments:

- a very basic load balancing http client
- a very basic long polling http client
    - a [Consul](https://www.consul.io/) API watcher using long polling

Feel free to use this code any way you want. This includes using it as an example of what not to do :smile:
