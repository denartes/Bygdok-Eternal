#pragma once

#include <adrenotools/driver.h>
#include <cstdlib>
#include <dlfcn.h>
#include <iostream>
#include <stdexcept>
#include <string>

static PFN_vkVoidFunction bygdok_vk_get_instance_proc_addr(VkInstance instance, const char *name) {
    static PFN_vkGetInstanceProcAddr get_proc = []() -> PFN_vkGetInstanceProcAddr {
        const char *driver_dir = std::getenv("BYGDOK_VULKAN_DRIVER_DIR");
        if (!driver_dir || !*driver_dir) {
            return ::vkGetInstanceProcAddr;
        }
        const char *driver_name = std::getenv("BYGDOK_VULKAN_DRIVER_NAME");
        const char *hook_dir = std::getenv("BYGDOK_VULKAN_HOOK_DIR");
        if (!driver_name || !*driver_name || !hook_dir || !*hook_dir) {
            throw std::runtime_error("Bygdok custom Vulkan driver configuration is incomplete");
        }
        const std::string loader_path = std::string(hook_dir) + "/libadrenotools.so";
        void *loader = dlopen(loader_path.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (!loader) {
            throw std::runtime_error(std::string("Bygdok could not load AdrenoTools: ") + dlerror());
        }
        auto open_driver = reinterpret_cast<decltype(&adrenotools_open_libvulkan)>(
            dlsym(loader, "adrenotools_open_libvulkan"));
        if (!open_driver) {
            throw std::runtime_error("Bygdok AdrenoTools entry point is missing");
        }
        const std::string directory = std::string(driver_dir) + "/";
        // Keep both handles alive: Vulkan entry points refer to their namespaces.
        void *vulkan = open_driver(RTLD_NOW | RTLD_LOCAL, ADRENOTOOLS_DRIVER_CUSTOM,
            nullptr, hook_dir, directory.c_str(), driver_name, nullptr, nullptr);
        if (!vulkan) {
            throw std::runtime_error("Bygdok AdrenoTools could not open the imported Vulkan driver");
        }
        auto proc = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(vulkan, "vkGetInstanceProcAddr"));
        if (!proc) {
            throw std::runtime_error("Bygdok custom Vulkan loader has no vkGetInstanceProcAddr");
        }
        std::cerr << "Bygdok: imported Vulkan driver requested: " << driver_name << std::endl;
        return proc;
    }();
    return get_proc(instance, name);
}

#define vkGetInstanceProcAddr bygdok_vk_get_instance_proc_addr
