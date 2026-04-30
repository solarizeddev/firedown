package com.solarized.firedown.utils;

import android.os.Bundle;
import android.util.Log;

import androidx.annotation.IdRes;
import androidx.navigation.NavAction;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.NavDirections;
import androidx.navigation.NavGraph;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.FragmentNavigator;

import com.solarized.firedown.R;


public abstract class NavigationUtils {

    private static final String TAG = NavigationUtils.class.getName();

    /**
     * This function will check navigation safety before starting navigation using direction
     *
     * @param navController NavController instance
     * @param direction     navigation operation
     */
    public static void navigateSafe(NavController navController, NavDirections direction) {

        if(navController == null)
            return;

        NavDestination currentDestination = navController.getCurrentDestination();

        if (currentDestination != null) {
            NavAction navAction = currentDestination.getAction(direction.getActionId());

            if (navAction != null) {
                int destinationId = navAction.getDestinationId();

                NavGraph currentNode;
                if (currentDestination instanceof NavGraph)
                    currentNode = (NavGraph) currentDestination;
                else
                    currentNode = currentDestination.getParent();

                if (destinationId != 0 && currentNode != null && currentNode.findNode(destinationId) != null) {
                    navController.navigate(direction);
                }
            }
        }
    }


    public static boolean checkCurrentDestination(NavController navController, int currentId){
        if(navController == null)
            return false;

        NavDestination currentDestination = navController.getCurrentDestination();

        if (currentDestination != null) {
            int id = currentDestination.getId();
            return id == currentId;
        }

        return false;
    }

    public static void navigateSafe(NavController navController, @IdRes int resId, @IdRes int currentId) {

        if(navController == null)
            return;

        NavDestination currentDestination = navController.getCurrentDestination();

        if (currentDestination != null) {

            int id = currentDestination.getId();

            if(id != currentId)
                return;

            NavAction navAction = currentDestination.getAction(resId);

            if (navAction != null) {
                int destinationId = navAction.getDestinationId();

                NavGraph currentNode;
                if (currentDestination instanceof NavGraph)
                    currentNode = (NavGraph) currentDestination;
                else
                    currentNode = currentDestination.getParent();

                if (destinationId != 0 && currentNode != null && currentNode.findNode(destinationId) != null) {
                    navController.navigate(resId);
                }
            }else{
                NavGraph currentNode;
                if (currentDestination instanceof NavGraph)
                    currentNode = (NavGraph) currentDestination;
                else
                    currentNode = currentDestination.getParent();

                if (currentNode != null && currentNode.findNode(resId) != null) {
                    navController.navigate(resId);
                }
            }
        }
    }

    public static void navigateSafe(NavController navController, @IdRes int resId) {

        if(navController == null)
            return;

        NavDestination currentDestination = navController.getCurrentDestination();

        if (currentDestination != null) {

            Log.d(TAG, "navigateSafe currentDestination: " + currentDestination.toString());

            NavAction navAction = currentDestination.getAction(resId);

            if (navAction != null) {
                int destinationId = navAction.getDestinationId();

                NavGraph currentNode;
                if (currentDestination instanceof NavGraph)
                    currentNode = (NavGraph) currentDestination;
                else
                    currentNode = currentDestination.getParent();

                if (destinationId != 0 && currentNode != null && currentNode.findNode(destinationId) != null) {
                    navController.navigate(resId);
                }
            }else{
                NavGraph currentNode;
                if (currentDestination instanceof NavGraph)
                    currentNode = (NavGraph) currentDestination;
                else
                    currentNode = currentDestination.getParent();

                if (currentNode != null && currentNode.findNode(resId) != null) {
                    navController.navigate(resId);
                }
            }
        }else{

            Log.e(TAG, "navigateSafe currentDestination is null");
        }
    }


    /**
     * This function will check navigation safety before starting navigation using resId and args bundle
     *
     * @param navController NavController instance
     * @param resId         destination resource id
     * @param args          bundle args
     */
    public static void navigateSafe(NavController navController, @IdRes int resId, Bundle args) {

        if(navController == null)
            return;

        NavDestination currentDestination = navController.getCurrentDestination();

        if (currentDestination != null) {
            NavAction navAction = currentDestination.getAction(resId);

            if (navAction != null) {
                int destinationId = navAction.getDestinationId();

                NavGraph currentNode;
                if (currentDestination instanceof NavGraph)
                    currentNode = (NavGraph) currentDestination;
                else
                    currentNode = currentDestination.getParent();

                if (destinationId != 0 && currentNode != null && currentNode.findNode(destinationId) != null) {
                    navController.navigate(resId, args);
                }
            }else{
                NavGraph currentNode;
                if (currentDestination instanceof NavGraph)
                    currentNode = (NavGraph) currentDestination;
                else
                    currentNode = currentDestination.getParent();

                if (currentNode != null && currentNode.findNode(resId) != null) {
                    navController.navigate(resId, args);
                }
            }
        }
    }


    public static void navigateSafe(NavController navController, @IdRes int resId, int currentId, Bundle args) {

        if(navController == null)
            return;

        NavDestination currentDestination = navController.getCurrentDestination();

        if (currentDestination != null) {
            NavAction navAction = currentDestination.getAction(resId);

            int id = currentDestination.getId();

            if(id != currentId)
                return;

            if (navAction != null) {
                int destinationId = navAction.getDestinationId();

                NavGraph currentNode;
                if (currentDestination instanceof NavGraph)
                    currentNode = (NavGraph) currentDestination;
                else
                    currentNode = currentDestination.getParent();

                if (destinationId != 0 && currentNode != null && currentNode.findNode(destinationId) != null) {
                    navController.navigate(resId, args);
                }
            }else{
                NavGraph currentNode;
                if (currentDestination instanceof NavGraph)
                    currentNode = (NavGraph) currentDestination;
                else
                    currentNode = currentDestination.getParent();

                if (currentNode != null && currentNode.findNode(resId) != null) {
                    navController.navigate(resId, args);
                }
            }
        }
    }


    public static void navigateSafe(NavController navController, @IdRes int resId, Bundle args, FragmentNavigator.Extras extras) {

        if(navController == null)
            return;

        NavDestination currentDestination = navController.getCurrentDestination();

        if (currentDestination != null) {
            NavAction navAction = currentDestination.getAction(resId);

            if (navAction != null) {
                int destinationId = navAction.getDestinationId();

                NavGraph currentNode;
                if (currentDestination instanceof NavGraph)
                    currentNode = (NavGraph) currentDestination;
                else
                    currentNode = currentDestination.getParent();

                if (destinationId != 0 && currentNode != null && currentNode.findNode(destinationId) != null) {
                    navController.navigate(resId, args, null, extras);
                }
            }else{
                NavGraph currentNode;
                if (currentDestination instanceof NavGraph)
                    currentNode = (NavGraph) currentDestination;
                else
                    currentNode = currentDestination.getParent();

                if (currentNode != null && currentNode.findNode(resId) != null) {
                    navController.navigate(resId, args,null, extras);
                }
            }
        }
    }

    public static void popBackStackSafe(NavController navController, int id){

        if(navController == null)
            return;

        NavDestination navDestination = navController.getCurrentDestination();
        if(navDestination != null){
            int currentId = navDestination.getId();
            if(id == currentId) navController.popBackStack();
        }
    }

    private static int orEmpty(Integer value) {
        return value == null ? 0 : value;
    }
}
