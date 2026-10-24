# libtermux：Termux terminal-emulator 的 pty JNI 支持（Apache-2.0）
# 提供 create_subprocess（forkpty）/ waitpid / set window size / send signal。
cmake_minimum_required(VERSION 3.22)
project(termux LANGUAGES C)

add_library(termux SHARED termux.c)

target_compile_options(termux PRIVATE
  -std=c11
  -Wall
  -Wextra
  -Os
  -fno-stack-protector
  -Wl,--gc-sections
)
