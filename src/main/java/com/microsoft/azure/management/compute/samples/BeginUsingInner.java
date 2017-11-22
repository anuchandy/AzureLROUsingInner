package com.microsoft.azure.management.compute.samples;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.joda.JodaModule;
import com.google.common.reflect.TypeToken;
import com.microsoft.azure.AzureClient;
import com.microsoft.azure.PollingState;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.compute.KnownLinuxVirtualMachineImage;
import com.microsoft.azure.management.compute.VirtualMachine;
import com.microsoft.azure.management.compute.VirtualMachineSizeTypes;
import com.microsoft.azure.management.compute.implementation.OperationStatusResponseInner;
import com.microsoft.azure.management.compute.implementation.VirtualMachinesInner;
import com.microsoft.azure.management.resources.fluentcore.arm.Region;
import com.microsoft.azure.management.resources.fluentcore.utils.SdkContext;
import com.microsoft.azure.management.resources.implementation.ResourceGroupsInner;
import com.microsoft.azure.management.samples.Utils;
import com.microsoft.rest.LogLevel;
import com.microsoft.rest.ServiceResponse;
import com.microsoft.rest.protocol.SerializerAdapter;
import com.microsoft.rest.serializer.Base64UrlSerializer;
import com.microsoft.rest.serializer.ByteArraySerializer;
import com.microsoft.rest.serializer.DateTimeRfc1123Serializer;
import com.microsoft.rest.serializer.DateTimeSerializer;
import com.microsoft.rest.serializer.HeadersSerializer;
import okhttp3.ResponseBody;
import retrofit2.Response;

import java.io.File;
import java.io.IOException;

public final class BeginUsingInner {

    public static void main(String[] args) {
        try {
            final File credFile = new File(System.getenv("AZURE_AUTH_LOCATION"));
            Azure azure = Azure.configure()
                    .withLogLevel(LogLevel.BODY_AND_HEADERS)
                    .authenticate(credFile)
                    .withDefaultSubscription();
            System.out.println("Selected subscription: " + azure.subscriptionId());
            runSample(azure);

        } catch (Exception e) {
            System.out.println(e.getMessage());
            e.printStackTrace();
        }
    }

    public static boolean runSample(Azure azure) throws Exception {
        final Region region = Region.US_EAST;
        final String rgName = Utils.createRandomName("rgCOMV");
        final String userName = "tirekicker";
        final String password = "testAnv!%";

        try {
            final String linuxVM1Name = SdkContext.randomResourceName("vm" + "-", 18);

            VirtualMachine linuxVM = azure.virtualMachines()
                    .define(linuxVM1Name)
                    .withRegion(region)
                    .withNewResourceGroup(rgName)
                    .withNewPrimaryNetwork("10.0.0.0/28")
                    .withPrimaryPrivateIPAddressDynamic()
                    .withoutPrimaryPublicIPAddress()
                    .withPopularLinuxImage(KnownLinuxVirtualMachineImage.UBUNTU_SERVER_16_04_LTS)
                    .withRootUsername(userName)
                    .withRootPassword(password)
                    .withSize(VirtualMachineSizeTypes.STANDARD_D3_V2)
                    .create();


            AzureClient azureInnerClient = azure.virtualMachines().manager().inner().getAzureClient();

            // LRO using inner - example 1 (De-allocating virtual machine)

            VirtualMachinesInner virtualMachineInnerClient = azure.virtualMachines().inner();

            ServiceResponse<OperationStatusResponseInner> vmDeallocateServiceResponse = virtualMachineInnerClient
                    .beginDeallocateWithServiceResponseAsync(linuxVM.resourceGroupName(), linuxVM.name())
                    .toBlocking()
                    .last();

            InitialPollingState<OperationStatusResponseInner> initialPollingStateForVMDeallocate =
                    new InitialPollingState<>(vmDeallocateServiceResponse.body(), vmDeallocateServiceResponse.response(), azureInnerClient);

            String serializedDeallocateVMPoll = initialPollingStateForVMDeallocate.serialize();

            System.out.println(serializedDeallocateVMPoll);

            PollingState<OperationStatusResponseInner> pollingStateVMDeallocate = PollingState.createFromJSONString(serializedDeallocateVMPoll);

            Thread.sleep(6 * 1000);

            // Poll first
            //
            pollingStateVMDeallocate = azureInnerClient
                    .pollSingleAsync(pollingStateVMDeallocate, new TypeToken<OperationStatusResponseInner>() { }.getType())
                    .toBlocking()
                    .value();

            System.out.println(pollingStateVMDeallocate.serialize());

            Thread.sleep(6 * 1000);

            // Poll second
            //
            pollingStateVMDeallocate = azureInnerClient
                    .pollSingleAsync(pollingStateVMDeallocate, new TypeToken<OperationStatusResponseInner>() { }.getType())
                    .toBlocking()
                    .value();

            System.out.println(pollingStateVMDeallocate.serialize());

            // LRO using example 2 (Deleting resource group]
            //

            ResourceGroupsInner resourceGroupInnerClient = azure.virtualMachines()
                    .manager()
                    .resourceManager()
                    .inner()
                    .resourceGroups();

            ServiceResponse<Void> deleteRGServiceResponse = resourceGroupInnerClient
                    .beginDeleteWithServiceResponseAsync(rgName)
                    .toBlocking()
                    .last();

            InitialPollingState<Void> initialPollingStateRGDelete =
                    new InitialPollingState<>(deleteRGServiceResponse.body(), deleteRGServiceResponse.response(), azureInnerClient);

            String serializedRGDeletePoll = initialPollingStateRGDelete.serialize();

            System.out.println(serializedDeallocateVMPoll);

            PollingState<Void> pollingStateRGDelete = PollingState.createFromJSONString(serializedRGDeletePoll);

            int count = 0;
            // Poll 10 times
            //
            while (count < 10) {

                pollingStateRGDelete = azureInnerClient
                        .pollSingleAsync(pollingStateRGDelete, new TypeToken<Void>() { }.getType())
                        .toBlocking()
                        .value();

                System.out.println(count + ": -> " + pollingStateRGDelete.serialize());
                Thread.sleep(6 * 1000);
                count++;
            }

            return true;
        } catch (Exception f) {
            System.out.println(f.getMessage());
            f.printStackTrace();
        } finally {
        }
        return false;
    }


    public static class PollingResource {
        @JsonProperty(value = "provisioningState")
        private String provisioningState;

        public PollingResource() {}
    }

    public static class InitialPollingState<T> {
        String loggingContext;
        String initialHttpMethod;
        String status;
        int statusCode;
        String putOrPatchResourceUri;
        String azureAsyncOperationHeaderLink;
        String locationHeaderLink;
        int retryTimeout;

        @JsonIgnore
        private final SerializerAdapter<?> serializeAdapter;

        public InitialPollingState(T instance,
                                   Response<ResponseBody> response,
                                   AzureClient client) throws IOException {
            this.serializeAdapter = client.serializerAdapter();
            PollingResource resource = null;
            if (instance != null) {
                String instanceStr = serializeAdapter.serialize(instance);
                resource = serializeAdapter.deserialize(instanceStr, PollingResource.class);
            }

            this.initialHttpMethod = response.raw().request().method();
            this.loggingContext = response.raw().request().header("x-ms-logging-context");
            this.statusCode = response.code();

            if (resource != null && resource.provisioningState != null) {
                this.status = resource.provisioningState;
            } else {
                switch (this.statusCode) {
                    case 202:
                        this.status = "InProgress";
                        break;
                    case 204:
                    case 201:
                    case 200:
                        this.status = "Succeeded";
                        break;
                    default:
                        this.status = "Failed";
                }
            }

            if (this.initialHttpMethod.equalsIgnoreCase("PUT")
                    || this.initialHttpMethod.equalsIgnoreCase("PATCH")) {
                this.putOrPatchResourceUri = response.raw().request().url().toString();
            }

            String asyncHeader = response.headers().get("Azure-AsyncOperation");
            String locationHeader = response.headers().get("Location");
            if (asyncHeader != null) {
                this.azureAsyncOperationHeaderLink = asyncHeader;
            }
            if (locationHeader != null) {
                this.locationHeaderLink = locationHeader;
            }
            if (response.headers().get("Retry-After") != null) {
                this.retryTimeout = Integer.parseInt(response.headers().get("Retry-After")) * 1000;
            } else {
                this.retryTimeout = -1;
            }
        }

        public String serialize() {
            ObjectMapper mapper = initMapper(new ObjectMapper());
            try {
                return mapper.writeValueAsString(this);
            } catch (JsonProcessingException exception) {
                throw new RuntimeException(exception);
            }
        }

        private ObjectMapper initMapper(ObjectMapper mapper) {
            mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                    .configure(SerializationFeature.WRITE_EMPTY_JSON_ARRAYS, true)
                    .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                    .registerModule(new JodaModule())
                    .registerModule(ByteArraySerializer.getModule())
                    .registerModule(Base64UrlSerializer.getModule())
                    .registerModule(DateTimeSerializer.getModule())
                    .registerModule(DateTimeRfc1123Serializer.getModule())
                    .registerModule(HeadersSerializer.getModule());
            mapper.setVisibility(mapper.getSerializationConfig().getDefaultVisibilityChecker()
                    .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                    .withSetterVisibility(JsonAutoDetect.Visibility.NONE)
                    .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                    .withIsGetterVisibility(JsonAutoDetect.Visibility.NONE));
            return mapper;
        }
    }
}