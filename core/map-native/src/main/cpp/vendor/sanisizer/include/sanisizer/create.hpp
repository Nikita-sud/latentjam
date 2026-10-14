#ifndef SANISIZER_CREATE_HPP
#define SANISIZER_CREATE_HPP

#include <utility>

#include "utils.hpp"
#include "cast.hpp"

/**
 * @file create.hpp
 * @brief Safely create and resize containers.
 */

namespace sanisizer {

/**
 * Cast an integer to the size type of a container.
 * This protects against overflow when using this integer in the container's constructor or `resize()`/`reserve()` methods.
 *
 * @tparam Container_ Container class with a `size()` method and a constructor that accepts the size as the first argument.
 * @tparam Value_ Integer type of the input size.
 *
 * @param x Non-negative value representing the desired container size.
 *
 * @return `x` as the container's size's type.
 */
template<typename Container_, typename Value_>
constexpr auto as_size_type(Value_ x) {
    return cast<I<decltype(std::declval<Container_>().size())> >(x);
}

/**
 * Create a new container of a specified size.
 * This protects against overflow when casting the integer size to the container's size type, see `as_size_type()` for details.
 *
 * @tparam Container_ Container class with a `size()` method and a constructor that accepts the size as the first argument.
 * @tparam Value_ Integer type of the input size.
 * @tparam Args_ Further arguments to pass to the container's constructor.
 *
 * @param x Non-negative value representing the desired container size.
 * @param args Additional arguments to pass to the `Container_` constructor after the size.
 *
 * @return An instance of the container, constructed to be of size `x`.
 */
template<class Container_, typename Value_, typename ... Args_>
Container_ create(Value_ x, Args_&&... args) {
    return Container_(as_size_type<Container_>(x), std::forward<Args_>(args)...);
}

/**
 * Resize a container to the desired size.
 * This protects against overflow when casting the integer size to the container's size type, see `as_size_type()` for details.
 *
 * @tparam Container_ Container class with a `size()` method and a `resize()` method that accepts the size as the first argument.
 * @tparam Value_ Integer type of the input size.
 * @tparam Args_ Further arguments to pass to the container's `resize()` method.
 *
 * @param container An existing instance of the container.
 * This is resized to size `x`.
 * @param x Non-negative value representing the desired container size.
 * @param args Additional arguments to pass to `resize()` after the size.
 */
template<class Container_, typename Value_, typename ... Args_>
void resize(Container_& container, Value_ x, Args_&&... args) {
    container.resize(as_size_type<Container_>(x), std::forward<Args_>(args)...);
}

/**
 * Reserve a container to the desired size.
 * This protects against overflow when casting the integer size to the container's size type, see `as_size_type()` for details.
 *
 * In most cases involving `std::vector`, this protection is not necessary as the size type is unsigned and `x` would harmlessly wrap around to some smaller non-negative value.
 * The reserved size would be smaller than `x` but would silently reallocate as necessary upon `push_back()` or `insert()` operations beyond that size.
 * However, this function becomes useful when the user wants to ensure that no reallocations occur, either for performance reasons or to maintain iterator/pointer validity.
 *
 * @tparam Container_ Container class with a `size()` method and a `reserve()` method that accepts the size as the first argument.
 * @tparam Value_ Integer type of the input size.
 * @tparam Args_ Further arguments to pass to the container's `reserve()` method.
 *
 * @param container An existing instance of the container.
 * On return, its allocation is set to `x`.
 * @param x Non-negative value representing the desired container size.
 * @param args Additional arguments to pass to `reserve()` after the size.
 */
template<class Container_, typename Value_, typename ... Args_>
void reserve(Container_& container, Value_ x, Args_&&... args) {
    container.reserve(as_size_type<Container_>(x), std::forward<Args_>(args)...);
}

}

#endif
