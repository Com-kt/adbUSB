#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <vulkan/vulkan.h>
#include <vector>
#include <cstring>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "VulkanSharpen", __VA_ARGS__)

#include "sharpen_spv.h"

uint32_t findMemoryType(VkPhysicalDevice gpu, uint32_t typeFilter, VkMemoryPropertyFlags properties) {
    VkPhysicalDeviceMemoryProperties memProperties;
    vkGetPhysicalDeviceMemoryProperties(gpu, &memProperties);
    for (uint32_t i = 0; i < memProperties.memoryTypeCount; i++) {
        if ((typeFilter & (1 << i)) && (memProperties.memoryTypes[i].propertyFlags & properties) == properties) {
            return i;
        }
    }
    return 0;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_adb_kitty_compose_data_NativeLibs_sharpenBitmapNative(
        JNIEnv *env, jobject thiz, jobject inputBitmap, jobject outputBitmap) {

    AndroidBitmapInfo info;
    void* inputPixels = nullptr;
    void* outputPixels = nullptr;

    if (AndroidBitmap_getInfo(env, inputBitmap, &info) < 0 || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("Bitmap error, No for RGBA_8888");
        return;
    }
    AndroidBitmap_lockPixels(env, inputBitmap, &inputPixels);
    AndroidBitmap_lockPixels(env, outputBitmap, &outputPixels);

    uint32_t width = info.width;
    uint32_t height = info.height;
    VkDeviceSize bufferSize = width * height * 4;

    VkInstanceCreateInfo instanceInfo{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    VkInstance instance;
    vkCreateInstance(&instanceInfo, nullptr, &instance);

    uint32_t gpuCount = 0;
    vkEnumeratePhysicalDevices(instance, &gpuCount, nullptr);
    std::vector<VkPhysicalDevice> gpus(gpuCount);
    vkEnumeratePhysicalDevices(instance, &gpuCount, gpus.data());
    VkPhysicalDevice physicalDevice = gpus[0];

    uint32_t queueFamilyCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &queueFamilyCount, nullptr);
    std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
    vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &queueFamilyCount, queueFamilies.data());
    uint32_t computeFamilyIndex = 0;
    for (uint32_t i = 0; i < queueFamilyCount; i++) {
        if (queueFamilies[i].queueFlags & VK_QUEUE_COMPUTE_BIT) {
            computeFamilyIndex = i;
            break;
        }
    }

    float queuePriority = 1.0f;
    VkDeviceQueueCreateInfo queueCreateInfo{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    queueCreateInfo.queueFamilyIndex = computeFamilyIndex;
    queueCreateInfo.queueCount = 1;
    queueCreateInfo.pQueuePriorities = &queuePriority;

    VkDeviceCreateInfo deviceInfo{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    deviceInfo.queueCreateInfoCount = 1;
    deviceInfo.pQueueCreateInfos = &queueCreateInfo;
    VkDevice device;
    vkCreateDevice(physicalDevice, &deviceInfo, nullptr, &device);

    VkQueue computeQueue;
    vkGetDeviceQueue(device, computeFamilyIndex, 0, &computeQueue);

    VkBufferCreateInfo bufCreateInfo{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bufCreateInfo.size = bufferSize;
    bufCreateInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    bufCreateInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    VkBuffer inBuffer, outBuffer;
    vkCreateBuffer(device, &bufCreateInfo, nullptr, &inBuffer);
    vkCreateBuffer(device, &bufCreateInfo, nullptr, &outBuffer);

    VkMemoryRequirements inReqs, outReqs;
    vkGetBufferMemoryRequirements(device, inBuffer, &inReqs);
    vkGetBufferMemoryRequirements(device, outBuffer, &outReqs);

    VkMemoryAllocateInfo allocInfo{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    allocInfo.allocationSize = inReqs.size;
    allocInfo.memoryTypeIndex = findMemoryType(physicalDevice, inReqs.memoryTypeBits, 
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

    VkDeviceMemory inMemory, outMemory;
    vkAllocateMemory(device, &allocInfo, nullptr, &inMemory);
    vkAllocateMemory(device, &allocInfo, nullptr, &outMemory);

    vkBindBufferMemory(device, inBuffer, inMemory, 0);
    vkBindBufferMemory(device, outBuffer, outMemory, 0);

    void* mappedInput = nullptr;
    vkMapMemory(device, inMemory, 0, bufferSize, 0, &mappedInput);
    std::memcpy(mappedInput, inputPixels, bufferSize);
    vkUnmapMemory(device, inMemory);

    VkDescriptorSetLayoutBinding bindings[2]{};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutCreateInfo dslInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    dslInfo.bindingCount = 2;
    dslInfo.pBindings = bindings;
    VkDescriptorSetLayout dsLayout;
    vkCreateDescriptorSetLayout(device, &dslInfo, nullptr, &dsLayout);

    VkPushConstantRange pushConstantRange{};
    pushConstantRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pushConstantRange.offset = 0;
    pushConstantRange.size = sizeof(uint32_t) * 2;

    VkPipelineLayoutCreateInfo plInfo{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    plInfo.setLayoutCount = 1;
    plInfo.pSetLayouts = &dsLayout;
    plInfo.pushConstantRangeCount = 1;
    plInfo.pPushConstantRanges = &pushConstantRange;
    VkPipelineLayout pipelineLayout;
    vkCreatePipelineLayout(device, &plInfo, nullptr, &pipelineLayout);

    VkShaderModuleCreateInfo smInfo{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    smInfo.codeSize = sizeof(kSharpenShaderCode);
    smInfo.pCode = kSharpenShaderCode;
    VkShaderModule shaderModule;
    vkCreateShaderModule(device, &smInfo, nullptr, &shaderModule);

    VkComputePipelineCreateInfo pipelineInfo{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    pipelineInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pipelineInfo.stage.stage = VK_STAGE_COMPUTE_BIT;
    pipelineInfo.stage.module = shaderModule;
    pipelineInfo.stage.pName = "main";
    pipelineInfo.layout = pipelineLayout;
    VkPipeline pipeline;
    vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &pipeline);

    VkDescriptorPoolSize poolSize{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 2};
    VkDescriptorPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    poolInfo.maxSets = 1;
    poolInfo.poolSizeCount = 1;
    poolInfo.pPoolSizes = &poolSize;
    VkDescriptorPool descriptorPool;
    vkCreateDescriptorPool(device, &poolInfo, nullptr, &descriptorPool);

    VkDescriptorSetAllocateInfo dsAllocInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    dsAllocInfo.descriptorPool = descriptorPool;
    dsAllocInfo.descriptorSetCount = 1;
    dsAllocInfo.pSetLayouts = &dsLayout;
    VkDescriptorSet descriptorSet;
    vkAllocateDescriptorSets(device, &dsAllocInfo, &descriptorSet);

    VkDescriptorBufferInfo inBufInfo{inBuffer, 0, VK_WHOLE_SIZE};
    VkDescriptorBufferInfo outBufInfo{outBuffer, 0, VK_WHOLE_SIZE};
    VkWriteDescriptorSet writes[2]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = descriptorSet;
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[0].pBufferInfo = &inBufInfo;

    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = descriptorSet;
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[1].pBufferInfo = &outBufInfo;
    vkUpdateDescriptorSets(device, 2, writes, 0, nullptr);

    VkCommandPoolCreateInfo cmdPoolInfo{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    cmdPoolInfo.queueFamilyIndex = computeFamilyIndex;
    VkCommandPool commandPool;
    vkCreateCommandPool(device, &cmdPoolInfo, nullptr, &commandPool);

    VkCommandBufferAllocateInfo cmdAllocInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    cmdAllocInfo.commandPool = commandPool;
    cmdAllocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmdAllocInfo.commandBufferCount = 1;
    VkCommandBuffer cmdBuffer;
    vkAllocateCommandBuffers(device, &cmdAllocInfo, &cmdBuffer);

    VkCommandBufferBeginInfo beginInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    vkBeginCommandBuffer(cmdBuffer, &beginInfo);

    vkCmdBindPipeline(cmdBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
    vkCmdBindDescriptorSets(cmdBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, 1, &descriptorSet, 0, nullptr);

    uint32_t pushData[2] = { width, height };
    vkCmdPushConstants(cmdBuffer, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(uint32_t) * 2, pushData);

    vkCmdDispatch(cmdBuffer, (width + 15) / 16, (height + 15) / 16, 1);
    vkEndCommandBuffer(cmdBuffer);

    VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    VkFence fence;
    vkCreateFence(device, &fenceInfo, nullptr, &fence);

    VkSubmitInfo submitInfo{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &cmdBuffer;
    vkQueueSubmit(computeQueue, 1, &submitInfo, fence);

    vkWaitForFences(device, 1, &fence, VK_TRUE, UINT64_MAX);

    void* mappedOutput = nullptr;
    vkMapMemory(device, outMemory, 0, bufferSize, 0, &mappedOutput);
    std::memcpy(outputPixels, mappedOutput, bufferSize);
    vkUnmapMemory(device, outMemory);

    AndroidBitmap_unlockPixels(env, inputBitmap);
    AndroidBitmap_unlockPixels(env, outputBitmap);

    vkDestroyFence(device, fence, nullptr);
    vkDestroyCommandPool(device, commandPool, nullptr);
    vkDestroyDescriptorPool(device, descriptorPool, nullptr);
    vkDestroyPipeline(device, pipeline, nullptr);
    vkDestroyShaderModule(device, shaderModule, nullptr);
    vkDestroyPipelineLayout(device, pipelineLayout, nullptr);
    vkDestroyDescriptorSetLayout(device, dsLayout, nullptr);
    vkFreeMemory(device, inMemory, nullptr);
    vkFreeMemory(device, outMemory, nullptr);
    vkDestroyBuffer(device, inBuffer, nullptr);
    vkDestroyBuffer(device, outBuffer, nullptr);
    vkDestroyDevice(device, nullptr);
    vkDestroyInstance(instance, nullptr);
}
