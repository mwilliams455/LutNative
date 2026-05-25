#include "vulkan_manager.h"
#include <android/log.h>
#include <cstring>
#include <stdexcept>

#define TAG "PLog_VulkanManager"

namespace {

bool hasExtension(const std::vector<VkExtensionProperties> &extensions,
                  const char *name) {
  for (const auto &extension : extensions) {
    if (std::strcmp(extension.extensionName, name) == 0) {
      return true;
    }
  }
  return false;
}

std::vector<VkExtensionProperties> enumerateInstanceExtensions() {
  uint32_t count = 0;
  vkEnumerateInstanceExtensionProperties(nullptr, &count, nullptr);
  std::vector<VkExtensionProperties> extensions(count);
  if (count > 0) {
    vkEnumerateInstanceExtensionProperties(nullptr, &count, extensions.data());
  }
  return extensions;
}

std::vector<VkExtensionProperties>
enumerateDeviceExtensions(VkPhysicalDevice physicalDevice) {
  uint32_t count = 0;
  vkEnumerateDeviceExtensionProperties(physicalDevice, nullptr, &count,
                                       nullptr);
  std::vector<VkExtensionProperties> extensions(count);
  if (count > 0) {
    vkEnumerateDeviceExtensionProperties(physicalDevice, nullptr, &count,
                                         extensions.data());
  }
  return extensions;
}

} // namespace

VulkanManager::~VulkanManager() { release(); }

bool VulkanManager::init() {
  if (instance != VK_NULL_HANDLE && physicalDevice != VK_NULL_HANDLE &&
      device != VK_NULL_HANDLE && commandPool != VK_NULL_HANDLE) {
    return true;
  }

  if (instance != VK_NULL_HANDLE || device != VK_NULL_HANDLE ||
      commandPool != VK_NULL_HANDLE) {
    LOGE("Discarding incomplete VulkanManager state before re-init");
    release();
  }

  LOGI("Initializing VulkanManager...");
  if (!createInstance()) {
    LOGE("Failed to create Vulkan instance");
    release();
    return false;
  }
  if (!pickPhysicalDevice()) {
    LOGE("Failed to pick physical device");
    release();
    return false;
  }
  if (!createLogicalDevice()) {
    LOGE("Failed to create logical device");
    release();
    return false;
  }
  if (!createCommandPool()) {
    LOGE("Failed to create command pool");
    release();
    return false;
  }

  LOGI("VulkanManager initialized successfully on device: %p", device);
  return true;
}

void VulkanManager::release() {
  if (commandPool != VK_NULL_HANDLE) {
    vkDestroyCommandPool(device, commandPool, nullptr);
    commandPool = VK_NULL_HANDLE;
  }
  if (device != VK_NULL_HANDLE) {
    vkDestroyDevice(device, nullptr);
    device = VK_NULL_HANDLE;
  }
  if (instance != VK_NULL_HANDLE) {
    vkDestroyInstance(instance, nullptr);
    instance = VK_NULL_HANDLE;
  }
  physicalDevice = VK_NULL_HANDLE;
  computeQueue = VK_NULL_HANDLE;
  computeQueueFamilyIndex = 0;
  hardwareBufferSupported = false;
}

bool VulkanManager::createInstance() {
  VkApplicationInfo appInfo{};
  appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
  appInfo.pApplicationName = "MyCamera";
  appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
  appInfo.pEngineName = "No Engine";
  appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
  appInfo.apiVersion = VK_API_VERSION_1_1;

  std::vector<const char *> extensions = {
      VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME,
      VK_KHR_EXTERNAL_MEMORY_CAPABILITIES_EXTENSION_NAME};

  auto availableExtensions = enumerateInstanceExtensions();
  for (const char *extension : extensions) {
    if (!hasExtension(availableExtensions, extension)) {
      LOGE("Required Vulkan instance extension is missing: %s", extension);
      return false;
    }
  }

  VkInstanceCreateInfo createInfo{};
  createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
  createInfo.pApplicationInfo = &appInfo;
  createInfo.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
  createInfo.ppEnabledExtensionNames = extensions.data();

  if (vkCreateInstance(&createInfo, nullptr, &instance) != VK_SUCCESS) {
    LOGE("Failed to create Vulkan instance");
    return false;
  }
  return true;
}

bool VulkanManager::pickPhysicalDevice() {
  uint32_t deviceCount = 0;
  vkEnumeratePhysicalDevices(instance, &deviceCount, nullptr);
  if (deviceCount == 0)
    return false;

  std::vector<VkPhysicalDevice> devices(deviceCount);
  vkEnumeratePhysicalDevices(instance, &deviceCount, devices.data());

  for (const auto &dev : devices) {
    VkPhysicalDeviceProperties deviceProperties;
    vkGetPhysicalDeviceProperties(dev, &deviceProperties);
    if (deviceProperties.deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU ||
        deviceProperties.deviceType == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU) {
      physicalDevice = dev;
      break;
    }
  }

  if (physicalDevice == VK_NULL_HANDLE)
    physicalDevice = devices[0];
  return true;
}

bool VulkanManager::createLogicalDevice() {
  uint32_t queueFamilyCount = 0;
  vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &queueFamilyCount,
                                           nullptr);
  std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
  vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &queueFamilyCount,
                                           queueFamilies.data());

  bool found = false;
  for (uint32_t i = 0; i < queueFamilyCount; i++) {
    if (queueFamilies[i].queueFlags & VK_QUEUE_COMPUTE_BIT) {
      computeQueueFamilyIndex = i;
      found = true;
      break;
    }
  }
  if (!found)
    return false;

  float queuePriority = 0.0f; // Lower priority
  auto availableExtensions = enumerateDeviceExtensions(physicalDevice);
  std::vector<const char *> requiredDeviceExtensions = {
      VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME,
      VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
      VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME};
  std::vector<const char *> optionalDeviceExtensions = {
      VK_KHR_MAINTENANCE1_EXTENSION_NAME,
      VK_KHR_BIND_MEMORY_2_EXTENSION_NAME,
      VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME};

  for (const char *extension : requiredDeviceExtensions) {
    if (!hasExtension(availableExtensions, extension)) {
      LOGE("Required Vulkan device extension is missing: %s", extension);
      return false;
    }
  }

  std::vector<const char *> deviceExtensions = requiredDeviceExtensions;
  for (const char *extension : optionalDeviceExtensions) {
    if (hasExtension(availableExtensions, extension)) {
      deviceExtensions.push_back(extension);
    } else {
      LOGI("Optional Vulkan extension is missing, continuing without %s",
           extension);
    }
  }

  VkDeviceQueueGlobalPriorityCreateInfoEXT globalPriorityInfo{};
  const bool enableGlobalPriority =
      hasExtension(availableExtensions, VK_EXT_GLOBAL_PRIORITY_EXTENSION_NAME);
  if (enableGlobalPriority) {
    globalPriorityInfo.sType =
        VK_STRUCTURE_TYPE_DEVICE_QUEUE_GLOBAL_PRIORITY_CREATE_INFO_EXT;
    globalPriorityInfo.globalPriority = VK_QUEUE_GLOBAL_PRIORITY_LOW_EXT;
    deviceExtensions.push_back(VK_EXT_GLOBAL_PRIORITY_EXTENSION_NAME);
  } else {
    LOGI("Optional Vulkan extension is missing, continuing without %s",
         VK_EXT_GLOBAL_PRIORITY_EXTENSION_NAME);
  }

  VkDeviceQueueCreateInfo queueCreateInfo{};
  queueCreateInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
  queueCreateInfo.pNext = enableGlobalPriority ? &globalPriorityInfo : nullptr;
  queueCreateInfo.queueFamilyIndex = computeQueueFamilyIndex;
  queueCreateInfo.queueCount = 1;
  queueCreateInfo.pQueuePriorities = &queuePriority;

  VkPhysicalDeviceFeatures deviceFeatures{};

  VkPhysicalDeviceSamplerYcbcrConversionFeatures ycbcrFeatures{};
  ycbcrFeatures.sType =
      VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES;
  ycbcrFeatures.samplerYcbcrConversion = VK_TRUE;

  VkDeviceCreateInfo createInfo{};
  createInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
  createInfo.pNext = &ycbcrFeatures;
  createInfo.queueCreateInfoCount = 1;
  createInfo.pQueueCreateInfos = &queueCreateInfo;
  createInfo.pEnabledFeatures = &deviceFeatures;
  createInfo.enabledExtensionCount =
      static_cast<uint32_t>(deviceExtensions.size());
  createInfo.ppEnabledExtensionNames = deviceExtensions.data();

  if (vkCreateDevice(physicalDevice, &createInfo, nullptr, &device) !=
      VK_SUCCESS) {
    LOGE("Failed to create logical device");
    return false;
  }

  vkGetDeviceQueue(device, computeQueueFamilyIndex, 0, &computeQueue);
  hardwareBufferSupported = true;
  return true;
}

bool VulkanManager::createCommandPool() {
  VkCommandPoolCreateInfo poolInfo{};
  poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
  poolInfo.queueFamilyIndex = computeQueueFamilyIndex;
  poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;

  if (vkCreateCommandPool(device, &poolInfo, nullptr, &commandPool) !=
      VK_SUCCESS) {
    LOGE("Failed to create command pool");
    return false;
  }
  return true;
}

uint32_t VulkanManager::findMemoryType(uint32_t typeFilter,
                                       VkMemoryPropertyFlags properties) {
  VkPhysicalDeviceMemoryProperties memProperties;
  vkGetPhysicalDeviceMemoryProperties(physicalDevice, &memProperties);
  for (uint32_t i = 0; i < memProperties.memoryTypeCount; i++) {
    if ((typeFilter & (1 << i)) && (memProperties.memoryTypes[i].propertyFlags &
                                    properties) == properties) {
      return i;
    }
  }
  LOGE("No matching Vulkan memory type. typeFilter=0x%x properties=0x%x",
       typeFilter, properties);
  throw std::runtime_error("No matching Vulkan memory type");
}

VkCommandBuffer VulkanManager::beginSingleTimeCommands() {
  VkCommandBufferAllocateInfo allocInfo{};
  allocInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
  allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
  allocInfo.commandPool = commandPool;
  allocInfo.commandBufferCount = 1;

  VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
  VkResult result = vkAllocateCommandBuffers(device, &allocInfo, &commandBuffer);
  if (result != VK_SUCCESS || commandBuffer == VK_NULL_HANDLE) {
    LOGE("Failed to allocate single-time command buffer: %d", result);
    throw std::runtime_error("Failed to allocate Vulkan command buffer");
  }

  VkCommandBufferBeginInfo beginInfo{};
  beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
  beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;

  result = vkBeginCommandBuffer(commandBuffer, &beginInfo);
  if (result != VK_SUCCESS) {
    vkFreeCommandBuffers(device, commandPool, 1, &commandBuffer);
    LOGE("Failed to begin single-time command buffer: %d", result);
    throw std::runtime_error("Failed to begin Vulkan command buffer");
  }
  return commandBuffer;
}

void VulkanManager::endSingleTimeCommands(VkCommandBuffer commandBuffer) {
  if (commandBuffer == VK_NULL_HANDLE) {
    throw std::runtime_error("Cannot submit null Vulkan command buffer");
  }

  VkResult result = vkEndCommandBuffer(commandBuffer);
  if (result != VK_SUCCESS) {
    vkFreeCommandBuffers(device, commandPool, 1, &commandBuffer);
    LOGE("Failed to end single-time command buffer: %d", result);
    throw std::runtime_error("Failed to end Vulkan command buffer");
  }

  VkSubmitInfo submitInfo{};
  submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
  submitInfo.commandBufferCount = 1;
  submitInfo.pCommandBuffers = &commandBuffer;

  result = vkQueueSubmit(computeQueue, 1, &submitInfo, VK_NULL_HANDLE);
  if (result != VK_SUCCESS) {
    vkFreeCommandBuffers(device, commandPool, 1, &commandBuffer);
    LOGE("Failed to submit single-time command buffer: %d", result);
    throw std::runtime_error("Failed to submit Vulkan command buffer");
  }

  result = vkQueueWaitIdle(computeQueue);
  if (result != VK_SUCCESS) {
    vkFreeCommandBuffers(device, commandPool, 1, &commandBuffer);
    LOGE("Failed while waiting for Vulkan queue idle: %d", result);
    throw std::runtime_error("Failed waiting for Vulkan queue");
  }

  vkFreeCommandBuffers(device, commandPool, 1, &commandBuffer);
}

VkShaderModule
VulkanManager::createShaderModule(const std::vector<uint32_t> &code) {
  VkShaderModuleCreateInfo createInfo{};
  createInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
  createInfo.codeSize = code.size() * sizeof(uint32_t);
  createInfo.pCode = code.data();

  VkShaderModule shaderModule;
  if (vkCreateShaderModule(device, &createInfo, nullptr, &shaderModule) !=
      VK_SUCCESS) {
    LOGE("Failed to create shader module");
    return VK_NULL_HANDLE;
  }
  return shaderModule;
}
