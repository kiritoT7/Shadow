package com.tencent.shadow.core.loader.blocs

import android.content.Context
import android.content.pm.PackageManager
import com.tencent.shadow.core.common.InstalledApk
import com.tencent.shadow.core.loader.LoadParameters
import com.tencent.shadow.core.loader.classloaders.InterfaceClassLoader
import com.tencent.shadow.core.loader.exceptions.LoadPluginException
import com.tencent.shadow.core.loader.infos.PluginParts
import com.tencent.shadow.core.loader.managers.CommonPluginPackageManager
import com.tencent.shadow.core.loader.managers.ComponentManager
import com.tencent.shadow.core.loader.managers.PluginBroadcastManager
import com.tencent.shadow.core.loader.managers.PluginPackageManager
import com.tencent.shadow.runtime.remoteview.ShadowRemoteViewCreatorProvider
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object LoadPluginBloc {
    @Throws(LoadPluginException::class)
    fun loadPlugin(
            executorService: ExecutorService,
            abi: String,
            commonPluginPackageManager: CommonPluginPackageManager,
            componentManager: ComponentManager,
            pluginBroadcastManager: PluginBroadcastManager,
            lock: ReentrantLock,
            pluginPartsMap: MutableMap<String, PluginParts>,
            hostAppContext: Context,
            installedApk: InstalledApk,
            loadParameters: LoadParameters,
            parentClassLoader: ClassLoader,
            remoteViewCreatorProvider: ShadowRemoteViewCreatorProvider?
    ): Future<*> {
        if (installedApk.apkFilePath == null) {
            throw LoadPluginException("apkFilePath==null")
        } else {
            val buildClassLoader = executorService.submit(Callable {
                lock.withLock {
                    LoadApkBloc.loadPlugin(hostAppContext, installedApk, loadParameters, parentClassLoader, pluginPartsMap)
                }
            })

            val getPackageInfo = executorService.submit(Callable {
                val archiveFilePath = installedApk.apkFilePath
                val packageManager = hostAppContext.packageManager
                val packageArchiveInfo = packageManager.getPackageArchiveInfo(
                        archiveFilePath,
                        PackageManager.GET_ACTIVITIES
                                or PackageManager.GET_META_DATA
                                or PackageManager.GET_SERVICES
                                or PackageManager.GET_PROVIDERS
                                or PackageManager.GET_SIGNATURES
                )
                        ?: throw NullPointerException("getPackageArchiveInfo return null.archiveFilePath==$archiveFilePath")
                packageArchiveInfo
            })

            val buildPackageManager = executorService.submit(Callable {
                val packageInfo = getPackageInfo.get()
                val pluginInfo = ParsePluginApkBloc.parse(packageInfo, loadParameters, hostAppContext)
                PluginPackageManager(commonPluginPackageManager, pluginInfo)
            })

            val buildResources = executorService.submit(Callable {
                val packageInfo = getPackageInfo.get()
                CreateResourceBloc.create(packageInfo, installedApk.apkFilePath, hostAppContext)
            })

            val buildApplication = executorService.submit(Callable {
                val pluginClassLoader = buildClassLoader.get()
                val pluginPackageManager = buildPackageManager.get()
                val resources = buildResources.get()
                val pluginInfo = pluginPackageManager.pluginInfo

                CreateApplicationBloc.createShadowApplication(
                        pluginClassLoader,
                        pluginInfo.applicationClassName,
                        pluginPackageManager,
                        resources,
                        hostAppContext,
                        componentManager,
                        pluginBroadcastManager.getBroadcastsByPartKey(pluginInfo.partKey),
                        remoteViewCreatorProvider
                )
            })

            val buildRunningPlugin = executorService.submit {
                if (File(installedApk.apkFilePath).exists().not()) {
                    throw LoadPluginException("插件文件不存在.pluginFile==" + installedApk.apkFilePath)
                }
                val pluginPackageManager = buildPackageManager.get()
                val pluginClassLoader = buildClassLoader.get()
                val resources = buildResources.get()
                val pluginInfo = pluginPackageManager.pluginInfo
                val shadowApplication = buildApplication.get()
                lock.withLock {
                    componentManager.addPluginApkInfo(pluginInfo)
                    pluginPartsMap[pluginInfo.partKey] = PluginParts(
                            pluginPackageManager,
                            shadowApplication,
                            pluginClassLoader,
                            resources
                    )
                }
            }

            return buildRunningPlugin
        }
    }

    fun loadInterface(
            executorService: ExecutorService,
            abi: String,
            hostAppContext: Context,
            comInterface: InterfaceClassLoader,
            installedApk: InstalledApk
    ): Future<*> {
        if (installedApk.apkFilePath == null) {
            throw LoadPluginException("apkFilePath==null")
        } else {

            return executorService.submit {
                val pluginLoaderClassLoader = LoadApkBloc::class.java.classLoader
                val hostAppParentClassLoader = pluginLoaderClassLoader.parent.parent
                val pluginClassLoader = LoadApkBloc.loadInterface(hostAppContext, installedApk, hostAppParentClassLoader)

                comInterface.addInterfaceClassLoader(pluginClassLoader)
            }

        }
    }


}