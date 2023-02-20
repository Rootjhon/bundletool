/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.tools.build.bundletool.device;

import static com.android.tools.build.bundletool.model.utils.ModuleDependenciesUtils.getModulesIncludingDependencies;
import static com.android.tools.build.bundletool.model.version.VersionGuardedFeature.NEW_DELIVERY_TYPE_MANIFEST_TAG;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.android.bundle.Commands.ApkDescription;
import com.android.bundle.Commands.ApkSet;
import com.android.bundle.Commands.AssetModuleMetadata;
import com.android.bundle.Commands.AssetSliceSet;
import com.android.bundle.Commands.BuildApksResult;
import com.android.bundle.Commands.DeliveryType;
import com.android.bundle.Commands.ModuleMetadata;
import com.android.bundle.Commands.PermanentlyFusedModule;
import com.android.bundle.Commands.Variant;
import com.android.bundle.Devices.DeviceSpec;
import com.android.bundle.Targeting.ApkTargeting;
import com.android.tools.build.bundletool.model.ModuleSplit;
import com.android.tools.build.bundletool.model.OptimizationDimension;
import com.android.tools.build.bundletool.model.ZipPath;
import com.android.tools.build.bundletool.model.exceptions.IncompatibleDeviceException;
import com.android.tools.build.bundletool.model.exceptions.InvalidCommandException;
import com.android.tools.build.bundletool.model.version.Version;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** Calculates whether a given device can be served an APK generated by the bundle tool. */
public class ApkMatcher {

  private final ImmutableList<? extends TargetingDimensionMatcher<?>> apkMatchers;

  private final Optional<ImmutableSet<String>> requestedModuleNames;
  private final boolean matchInstant;
  private final boolean includeInstallTimeAssetModules;
  private final ModuleMatcher moduleMatcher;
  private final VariantMatcher variantMatcher;
  private final boolean ensureDensityAndAbiApksMatched;

  public ApkMatcher(DeviceSpec deviceSpec) {
    this(
        deviceSpec,
        Optional.empty(),
        /* includeInstallTimeAssetModules= */ true,
        /* matchInstant= */ false,
        /* ensureDensityAndAbiApksMatched= */ false);
  }

  /**
   * Constructs an ApkMatcher.
   *
   * @param deviceSpec given device configuration
   * @param requestedModuleNames sets of modules to match, all modules if empty
   * @param matchInstant when set, matches APKs for instant modules only
   * @param ensureDensityAndAbiApksMatched when set, ensures one density split and/or one ABI split
   *     are matched per each module (if module has such splits) and throws
   *     IncompatibleDeviceException if not
   */
  public ApkMatcher(
      DeviceSpec deviceSpec,
      Optional<ImmutableSet<String>> requestedModuleNames,
      boolean includeInstallTimeAssetModules,
      boolean matchInstant,
      boolean ensureDensityAndAbiApksMatched) {
    checkArgument(
        !requestedModuleNames.isPresent() || !requestedModuleNames.get().isEmpty(),
        "Set of requested split modules cannot be empty.");
    SdkVersionMatcher sdkVersionMatcher = new SdkVersionMatcher(deviceSpec);
    AbiMatcher abiMatcher = new AbiMatcher(deviceSpec);
    MultiAbiMatcher multiAbiMatcher = new MultiAbiMatcher(deviceSpec);
    ScreenDensityMatcher screenDensityMatcher = new ScreenDensityMatcher(deviceSpec);
    LanguageMatcher languageMatcher = new LanguageMatcher(deviceSpec);
    DeviceFeatureMatcher deviceFeatureMatcher = new DeviceFeatureMatcher(deviceSpec);
    OpenGlFeatureMatcher openGlFeatureMatcher = new OpenGlFeatureMatcher(deviceSpec);
    TextureCompressionFormatMatcher textureCompressionFormatMatcher =
        new TextureCompressionFormatMatcher(deviceSpec);
    DeviceTierApkMatcher deviceTierApkMatcher = new DeviceTierApkMatcher(deviceSpec);
    CountrySetApkMatcher countrySetApkMatcher = new CountrySetApkMatcher(deviceSpec);
    DeviceGroupModuleMatcher deviceGroupModuleMatcher = new DeviceGroupModuleMatcher(deviceSpec);

    this.apkMatchers =
        ImmutableList.of(
            sdkVersionMatcher,
            abiMatcher,
            multiAbiMatcher,
            screenDensityMatcher,
            languageMatcher,
            textureCompressionFormatMatcher,
            deviceTierApkMatcher,
            countrySetApkMatcher);
    this.requestedModuleNames = requestedModuleNames;
    this.includeInstallTimeAssetModules = includeInstallTimeAssetModules;
    this.matchInstant = matchInstant;
    this.ensureDensityAndAbiApksMatched = ensureDensityAndAbiApksMatched;
    this.moduleMatcher =
        new ModuleMatcher(
            sdkVersionMatcher,
            deviceFeatureMatcher,
            openGlFeatureMatcher,
            deviceGroupModuleMatcher);
    this.variantMatcher =
        new VariantMatcher(
            sdkVersionMatcher,
            abiMatcher,
            multiAbiMatcher,
            screenDensityMatcher,
            textureCompressionFormatMatcher,
            new SdkRuntimeMatcher(deviceSpec),
            matchInstant);
  }

  /**
   * Returns all APKs that should be installed on a device.
   *
   * @param buildApksResult describes APKs produced by the BundleTool
   * @return paths of the matching APKs as represented by {@link ApkDescription#getPath()}
   */
  public ImmutableList<GeneratedApk> getMatchingApks(BuildApksResult buildApksResult) {
    Optional<Variant> matchingVariant = variantMatcher.getMatchingVariant(buildApksResult);

    matchingVariant.ifPresent(variant -> validateVariant(variant, buildApksResult));

    ImmutableList<GeneratedApk> variantApks =
        matchingVariant.isPresent()
            ? getMatchingApksFromVariant(
                matchingVariant.get(), Version.of(buildApksResult.getBundletool().getVersion()))
            : ImmutableList.of();

    ImmutableList<GeneratedApk> assetModuleApks =
        getMatchingApksFromAssetModules(buildApksResult.getAssetSliceSetList());

    return ImmutableList.<GeneratedApk>builder()
        .addAll(variantApks)
        .addAll(assetModuleApks)
        .build();
  }

  public ImmutableList<GeneratedApk> getMatchingApksFromVariant(
      Variant variant, Version bundleVersion) {
    ImmutableSet<String> modulesToMatch =
        matchInstant
            ? getRequestedInstantModulesWithDependencies(variant)
            : getInstallTimeAndRequestedModulesWithDependencies(variant, bundleVersion);

    return variant.getApkSetList().stream()
        .filter(apkSet -> modulesToMatch.contains(apkSet.getModuleMetadata().getName()))
        .flatMap(apkSet -> getMatchingApksFromModule(apkSet).stream())
        .collect(toImmutableList());
  }

  private ImmutableList<GeneratedApk> getMatchingApksFromModule(ApkSet moduleApks) {
    String moduleName = moduleApks.getModuleMetadata().getName();
    ImmutableList<ApkDescription> matchedApks =
        moduleApks.getApkDescriptionList().stream()
            .peek(apkDescription -> checkCompatibleWithApkTargeting(apkDescription.getTargeting()))
            .filter(apkDescription -> matchesApkTargeting(apkDescription.getTargeting()))
            .collect(toImmutableList());

    if (ensureDensityAndAbiApksMatched) {
      ImmutableSet<OptimizationDimension> availableDimensions =
          getApkTargetingOnlyAbiAndDensity(moduleApks.getApkDescriptionList());
      ImmutableSet<OptimizationDimension> matchedDimensions =
          getApkTargetingOnlyAbiAndDensity(matchedApks);

      if (!availableDimensions.equals(matchedDimensions)) {
        throw IncompatibleDeviceException.builder()
            .withUserMessage(
                "Missing APKs for %s dimensions in the module '%s' for the provided device.",
                Sets.difference(availableDimensions, matchedDimensions), moduleName)
            .build();
      }
    }

    return matchedApks.stream()
        .map(
            apkDescription ->
                GeneratedApk.create(
                    ZipPath.create(apkDescription.getPath()),
                    moduleName,
                    moduleApks.getModuleMetadata().getDeliveryType()))
        .collect(toImmutableList());
  }

  private static ImmutableSet<OptimizationDimension> getApkTargetingOnlyAbiAndDensity(
      Collection<ApkDescription> apks) {
    return apks.stream()
        .map(ApkDescription::getTargeting)
        .flatMap(
            targeting -> {
              Stream.Builder<OptimizationDimension> dimensions = Stream.builder();
              if (targeting.hasAbiTargeting()) {
                dimensions.add(OptimizationDimension.ABI);
              }
              if (targeting.hasScreenDensityTargeting()) {
                dimensions.add(OptimizationDimension.SCREEN_DENSITY);
              }
              return dimensions.build();
            })
        .collect(toImmutableSet());
  }

  private ImmutableSet<String> getRequestedInstantModulesWithDependencies(Variant variant) {
    if (!requestedModuleNames.isPresent()) {
      // For instant matching, by default all modules from instant variant are matched.
      return variant.getApkSetList().stream()
          .map(apkSet -> apkSet.getModuleMetadata().getName())
          .collect(toImmutableSet());
    }
    return getModulesIncludingDependencies(variant, requestedModuleNames.get());
  }

  private ImmutableSet<String> getInstallTimeAndRequestedModulesWithDependencies(
      Variant variant, Version bundleVersion) {
    ImmutableSet<String> installTimeModules =
        buildModulesDeliveredInstallTime(variant, bundleVersion);
    ImmutableSet<String> explicitlyRequested = requestedModuleNames.orElse(ImmutableSet.of());

    // Always return all install time modules plus requested ones.
    return getModulesIncludingDependencies(
        variant, Sets.union(installTimeModules, explicitlyRequested));
  }

  private void validateVariant(Variant variant, BuildApksResult buildApksResult) {
    if (requestedModuleNames.isPresent()) {
      boolean isStandaloneVariant =
          variant.getApkSetList().stream()
              .flatMap(apkSet -> apkSet.getApkDescriptionList().stream())
              .anyMatch(
                  apkDescription ->
                      apkDescription.hasStandaloneApkMetadata()
                          || apkDescription.hasApexApkMetadata());
      if (isStandaloneVariant) {
        throw InvalidCommandException.builder()
            .withInternalMessage("Cannot restrict modules when the device matches a non-split APK.")
            .build();
      }

      Set<String> availableModules =
          Sets.union(
              Sets.union(
                  variant.getApkSetList().stream()
                      .map(ApkSet::getModuleMetadata)
                      .map(ModuleMetadata::getName)
                      .collect(toImmutableSet()),
                  buildApksResult.getAssetSliceSetList().stream()
                      .map(AssetSliceSet::getAssetModuleMetadata)
                      .map(AssetModuleMetadata::getName)
                      .collect(toImmutableSet())),
              buildApksResult.getPermanentlyFusedModulesList().stream()
                  .map(PermanentlyFusedModule::getName)
                  .collect(toImmutableSet()));
      Set<String> unknownModules = Sets.difference(requestedModuleNames.get(), availableModules);
      if (!unknownModules.isEmpty()) {
        throw InvalidCommandException.builder()
            .withInternalMessage(
                "The APK Set archive does not contain the following modules: %s", unknownModules)
            .build();
      }
    }
  }

  /** Builds a list of modules that will be delivered on installation. */
  private ImmutableSet<String> buildModulesDeliveredInstallTime(
      Variant variant, Version bundleVersion) {
    // Module dependency resolution can be skipped because install-time modules can't depend on
    // on-demand modules.
    return variant.getApkSetList().stream()
        .map(ApkSet::getModuleMetadata)
        .filter(moduleMetadata -> willBeDeliveredInstallTime(moduleMetadata, bundleVersion))
        .map(ModuleMetadata::getName)
        .collect(toImmutableSet());
  }

  private boolean willBeDeliveredInstallTime(ModuleMetadata moduleMetadata, Version bundleVersion) {
    boolean installTime =
        NEW_DELIVERY_TYPE_MANIFEST_TAG.enabledForVersion(bundleVersion)
            ? moduleMetadata.getDeliveryType().equals(DeliveryType.INSTALL_TIME)
            : !moduleMetadata.getOnDemandDeprecated();

    return installTime && moduleMatcher.matchesModuleTargeting(moduleMetadata.getTargeting());
  }

  private boolean matchesApkTargeting(ApkTargeting apkTargeting) {
    return apkMatchers.stream()
        .allMatch(matcher -> matcher.getApkTargetingPredicate().test(apkTargeting));
  }

  /**
   * Returns whether a given APK generated by the Bundle Tool matches the device targeting.
   *
   * @return whether the APK matches the device targeting
   */
  public boolean matchesModuleSplitByTargeting(ModuleSplit moduleSplit) {
    // Check device compatibility.
    variantMatcher.checkCompatibleWithVariantTargeting(moduleSplit.getVariantTargeting());
    checkCompatibleWithApkTargeting(moduleSplit.getApkTargeting());

    return variantMatcher.matchesVariantTargeting(moduleSplit.getVariantTargeting())
        && matchesApkTargeting(moduleSplit.getApkTargeting());
  }

  /**
   * Checks if a device is compatible with targeting of a given split, considering the targeting
   * alternatives.
   *
   * @throws IncompatibleDeviceException
   */
  public void checkCompatibleWithApkTargeting(ModuleSplit moduleSplit) {
    checkCompatibleWithApkTargeting(moduleSplit.getApkTargeting());
  }

  private void checkCompatibleWithApkTargeting(ApkTargeting apkTargeting) {
    apkMatchers.forEach(matcher -> checkCompatibleWithApkTargetingHelper(matcher, apkTargeting));
  }

  private static <T> void checkCompatibleWithApkTargetingHelper(
      TargetingDimensionMatcher<T> matcher, ApkTargeting apkTargeting) {
    matcher.checkDeviceCompatible(matcher.getTargetingValue(apkTargeting));
  }

  public ImmutableList<GeneratedApk> getMatchingApksFromAssetModules(
      Collection<AssetSliceSet> assetModules) {
    Set<String> assetModulesToMatch =
        Sets.union(
            requestedModuleNames.orElse(ImmutableSet.of()),
            includeInstallTimeAssetModules
                ? getUpfrontAssetModules(assetModules)
                : ImmutableSet.of());

    return assetModules.stream()
        .filter(
            assetModule ->
                assetModulesToMatch.contains(assetModule.getAssetModuleMetadata().getName()))
        .flatMap(
            assetModule ->
                assetModule.getApkDescriptionList().stream()
                    .filter(apkDescription -> matchesApkTargeting(apkDescription.getTargeting()))
                    .map(
                        apkDescription ->
                            GeneratedApk.create(
                                ZipPath.create(apkDescription.getPath()),
                                assetModule.getAssetModuleMetadata().getName(),
                                assetModule.getAssetModuleMetadata().getDeliveryType())))
        .collect(toImmutableList());
  }

  private static ImmutableSet<String> getUpfrontAssetModules(
      Collection<AssetSliceSet> assetModules) {
    return assetModules.stream()
        .filter(
            sliceSet ->
                sliceSet
                    .getAssetModuleMetadata()
                    .getDeliveryType()
                    .equals(DeliveryType.INSTALL_TIME))
        .map(sliceSet -> sliceSet.getAssetModuleMetadata().getName())
        .collect(toImmutableSet());
  }

  /** Describes an APK generated by `build-apks` command of bundletool */
  @AutoValue
  public abstract static class GeneratedApk {
    /** Path of the APK inside APKS (result of `build-apks`) output. */
    public abstract ZipPath getPath();

    public abstract String getModuleName();

    public abstract DeliveryType getDeliveryType();

    public static GeneratedApk create(ZipPath path, String moduleName, DeliveryType deliveryType) {
      return new AutoValue_ApkMatcher_GeneratedApk(path, moduleName, deliveryType);
    }
  }
}
