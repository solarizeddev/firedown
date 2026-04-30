#!/usr/bin/env python3

from pathlib import Path

search = []

replace = []


def case_insensitive_search_and_replace(file_path, search_word, replace_word):
   with open(file_path, 'r') as file:
      file_contents = file.read()

      updated_contents = file_contents.replace(search_word, replace_word)

   with open(file_path, 'w') as file:
      file.write(updated_contents)


def populate_lists(file_path):
    search.clear()
    replace.clear()
    # with open(file_path) as searchfile:
    #     for line in searchfile:
    #         left,sep,right = line.partition(';h1&gt;')
    #         if sep: # True iff 'Figure' in line
    #             l = len(';h1&gt;')
    #             index = line.index(';h1&gt;')
    #             search.append(line[index:index+l+1])
    #             replace.append(line[index:index+l] + line[index+l:index+l+1].upper())
    # print(search)
    # print(replace)


# populate_lists('haiku_strings.xml')
#
# for i,s in enumerate(search):
#
#     case_insensitive_search_and_replace('haiku_strings.xml', s , replace[i])

pathlist = Path('/home/pnm/Downloads/Signal-Android-main/app/src/main/res').rglob('strings.xml')

for path in pathlist:
    # because path is object not string
    path_in_str = str(path)
    print(path_in_str)
    populate_lists(path_in_str)
    case_insensitive_search_and_replace(path_in_str, 'DateUtils_minutes_ago' , 'interval_minutes_ago')
    # for i,s in enumerate(search):



