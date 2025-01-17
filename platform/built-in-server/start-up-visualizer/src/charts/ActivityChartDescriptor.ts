// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
import {Item} from "@/state/data"

export interface ActivityChartDescriptor {
  readonly label: string
  readonly id: string

  readonly sourceNames?: Array<string>

  readonly rotatedLabels?: boolean
  readonly groupByThread?: boolean
  readonly sourceHasPluginInformation?: boolean

  readonly nameTransformer?: (item: Item) => string
}

function getShortName(item: Item): string {
  const lastDotIndex = item.name.lastIndexOf(".")
  return lastDotIndex < 0 ? item.name : item.name.substring(lastDotIndex + 1)
}

// not as part of ItemChartManager.ts to reduce scope of changes on change
// (make sure that hot reloading will not reload all modules where `chartDescriptors` is used - especially `router`)
export const chartDescriptors: Array<ActivityChartDescriptor> = [
  {
    label: "Components",
    id: "components",
    sourceNames: ["appComponents", "projectComponents", "moduleComponents"],
    nameTransformer: getShortName,
  },
  {
    label: "Services",
    id: "services",
    sourceNames: ["appServices", "projectServices", "moduleServices"],
    nameTransformer: getShortName,
  },
  {
    label: "Extensions",
    id: "extensions",
    sourceNames: ["appExtensions", "projectExtensions", "moduleExtensions"],
    nameTransformer: getShortName,
  },
  {
    label: "Prepare App Init",
    id: "prepareAppInitActivities",
    groupByThread: true,
    sourceHasPluginInformation: false,
  },
  {
    label: "Options Top Hit Providers",
    id: "topHitProviders",
    sourceNames: ["appOptionsTopHitProviders", "projectOptionsTopHitProviders"],
    nameTransformer: getShortName,
  },
  {
    label: "Project Post-Startup",
    id: "projectPostStartupActivities",
    nameTransformer: getShortName,
  },
  {
    label: "Reopening Editors",
    id: "reopeningEditors",
    sourceHasPluginInformation: false,
  },
  {
    label: "GCs",
    id: "GCs",
    rotatedLabels: false,
  },
]
