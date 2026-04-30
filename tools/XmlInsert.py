#!/usr/bin/env python3

import xml.etree.ElementTree as ET

value = 'values-zh-rTW'

# Parse the existing XML file
tree = ET.parse(f'/home/pnm/Downloads/Signal-Android-main/app/src/main/res/{value}/strings.xml')
root = tree.getroot()

tree2 = ET.parse(f'/home/pnm/AndroidStudioProjects/Firedown/app/src/main/res/{value}/plurals.xml')
root2 = tree2.getroot()


element = root.find('plurals[@name="hours_ago"]')

# element2 = root.find('string[@name="interval_minutes_ago"]')

print(element.text)
# print(element2.text)

root2.append(element)

# root2.append(element2)

tree2.write(f'/home/pnm/AndroidStudioProjects/Firedown/app/src/main/res/{value}/plurals.xml', encoding='utf-8')


# Append the new book element to the root
# root.append(new_book)
#
# # Write the updated XML back to a file
# tree.write('library.xml')
